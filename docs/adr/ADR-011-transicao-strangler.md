# ADR-011: transição Strangler, com outbox e ACL, paridade numérica, reconciliação e rollback

Status: Aceito

Relaciona os ADR-003 (outbox e relay), ADR-007 (mensageria), ADR-012 (calendário) e a seção de transição da documentação.

## Contexto

O desafio, nos diferenciais, pede o desenho da solução da arquitetura de transição, quando necessária para migração de legado. O desenho descreve o Strangler Fig em quatro fases, mas a fase 2 afirma numa frase que o serviço novo grava na nova base e, por evento assíncrono, atualiza o banco legado para manter a retrocompatibilidade, o que é um dual-write exatamente do tipo que o núcleo rejeitou no ADR-003: dois armazenamentos, o novo e o legado, sem transação, sem outbox e sem estado intermediário. A fase 4 faz o decommissioning após validação de paridade dos relatórios, sem critério numérico, sem job de reconciliação e sem rollback.

Há uma ironia a registrar: o núcleo exige reconciliação no read-side, com o invariante de contagem, e a deixa ausente na transição, então o diferencial cai por incoerência com o resto da solução.

## Opções consideradas

**Opção A, dual-write direto entre legado e novo na fase 2.**
- A favor: parece simples, porque o serviço novo escreve nos dois.
- Contra: é a mesma falha do fluxo principal, porque um crash entre os writes dá divergência silenciosa entre a base nova e o legado, sem caminho de reparo, e contradiz o núcleo já corrigido (ADR-003). Rejeitada.

**Opção B, outbox e ACL na fase 2, com paridade numérica, reconciliação e rollback (escolhida).**
- A favor: reusa o mesmo padrão de outbox (ADR-003) para publicar ao legado; o legado é alcançado por um adaptador, como uma anti-corruption layer, que é um consumidor do evento e não um write direto; a paridade vira medida, com SQL e contadores, e não adjetivo; e o rollback é uma rota no API Gateway ou no ALB mais desligar o consumidor do legado.
- Contra: exige um consumidor de legado, que é a ACL, e um job de reconciliação, ou seja, mais dois artefatos a operar, e tem latência de propagação ao legado, com o mesmo bound condicional do ADR-003.

**Opção C, big-bang, com corte único e janela de indisponibilidade.**
- A favor: sem período de convivência e sem dual-write.
- Contra: contraria o Strangler e o requisito de fases sem indisponibilidade, e para um ledger de caixa a indisponibilidade é inaceitável. Rejeitada.

## Decisão

Adoto a Opção B. As regras canônicas da transição são estas.

1. Fase 2 com outbox, e não dual-write. O serviço de lançamentos grava `lancamento` e `outbox` na mesma transação (ADR-003). Um adaptador de legado, que é a ACL e um consumidor interno, lê os eventos publicados e traduz para o modelo do legado. O legado é atualizado por evento idempotente, com `processed_event` no lado da ACL, e nunca por um segundo `INSERT` na request. Não há write síncrono ao legado no caminho da request.
2. Paridade numérica, como critério de conclusão da fase 4. A paridade é medida, não afirmada:

```
paridade_dia = |saldo_novo(d) - saldo_legado(d)| <= epsilon, para todo d na janela de observacao
contagem: N(lancamentos_novo) == N(lancamentos_espelhados_legado)  (por Idempotency-Key)
```

O `epsilon`, que é a tolerância, por exemplo 0,00, com centavos exatos em `numeric(18,2)`, e a janela, por exemplo de 7 a 14 dias úteis, são declarados e registrados na seção de transição da documentação. Sem número, a fase 4 não pode declarar paridade.

3. Job de reconciliação que roda por `{comerciante_id, data}` sobre a janela de corte, compara o saldo novo com o legado e emite a divergência, sem corrigir em silêncio: divergência trava a fase 4 e abre incidente. Ele reusa a reconciliação do read-side (ADR-004 e ADR-007).
4. Rollback em cada fase: a rota no API Gateway ou no ALB volta ao legado, o consumidor da ACL é desligado e o novo continua gravando no próprio outbox sem propagar, e nenhuma fase apaga dado do legado antes do fecho da paridade. O RTO da reversão é uma troca de rota, em segundos, e não uma migração.
5. Coerência de data de negócio entre o novo e o legado, no ADR-012, com o mesmo fuso e event-time, senão o dia diverge e a paridade falha por fuso, e não por dinheiro.
6. Direção no desenho: a topologia mostra o legado atrás do roteador de borda e recebendo eventos pela ACL, com a seta do novo para o legado passando pelo adaptador, e nunca do banco para o legado.

A janela de observação e o `epsilon` são decisão de negócio do dono, que conclui a fase. Enquanto não estiverem ratificados, a fase 4 fica pendente, com dono o próprio dono e gatilho no fecho da fase 3.

## Consequências

Positivas:
- Coerência com o núcleo: a transição usa o mesmo outbox e a mesma ACL, sem duplicar o problema que o núcleo corrigiu, e o diferencial de transição deixa de contradizer o desenho alvo.
- A paridade fica verificável, com número e janela, o que dá à fase 4 um critério objetivo, e o rollback é barato, por rota.
- Há uma única unidade de escrita, a base nova, por evento, e o legado é derivado, idempotente e reconciliável.

Negativas e mitigação:
- São dois consumidores do mesmo evento, o consolidado e a ACL do legado, então a ACL é mais uma aplicação a operar e observar, o que entra no ADR-009 e no ADR-013; a mitigação é reusar a biblioteca de consumo idempotente.
- Há latência de propagação ao legado, com o mesmo bound condicional do ADR-003, e não incondicional.
- O schema do legado é desconhecido no papel, e a ACL existe justamente para isolar isso, como anti-corruption layer; a tradução é um contrato a ser levantado com o dono, e o legado real não tem prova.

## Fontes

- O PDF do desafio, arquivo desafio-arquiteto-solucao-jun25.pdf, nos requisitos diferenciais, sobre a arquitetura de transição.
- Martin Fowler, StranglerFigApplication (martinfowler.com), sobre roteamento incremental e desligamento.
- Eric Evans, Domain-Driven Design (2003), sobre a anti-corruption layer para isolar o modelo legado.
- Chris Richardson, microservices.io, sobre Transactional Outbox e Idempotent Consumer, reusados na ACL.
- Google SRE Book, sobre canary releases e validação por janela de observação com critério numérico.
- Michael Nygard, Documenting Architecture Decisions (2011).
