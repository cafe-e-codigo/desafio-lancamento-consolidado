# O por quê da solução Verety

Leitura: este texto conta o porquê; a norma está em `especificacao-tecnica.md` e as decisões em `adr/`.
Régua: o PDF prevalece; ≤5% de perda é literal, medida no consolidado a 50 req/s; mono-comerciante; Java; sem frontend.

---

## 1. Por que dois serviços (e não um)

O PDF não pede "microsserviços": pede dois serviços e impõe um RNF que, na prática, é um requisito de topologia, o de que o controle de lançamento não deve ficar indisponível se o consolidado cair. Esse "não ficar indisponível se o outro cair" só é garantível se os dois forem processos e deploys separados. Monolito modular cai junto, e por isso a escolha é CQRS com segregação física (ADR-001). O segundo RNF, de 50 req/s no consolidado, pede escala assimétrica, porque só o lado de leitura escala. Dois serviços respondem aos dois RNFs de forma direta; qualquer desenho de um só processo exigiria contorção para fingir o isolamento.

O custo que eu aceito é consistência eventual e complexidade distribuída, com outbox, idempotência e DLQ. Não escondo isso: é o preço do RNF#1, e é pago pelas correções canônicas, não por mais componentes.

## 2. Por que o meu primeiro desenho precisou de correções (não foi reescrito)

O meu primeiro desenho acerta a estrutura, com dois serviços, CQRS e fila. Mas três mecanismos estavam quebrados de forma verificável, e eu os provei:

1. "Atualiza atomicamente Redis e Aurora" é dual-write. Dois stores, sem transação distribuída: um commita, o outro falha, e há divergência silenciosa. A correção é um único write target, o Postgres, com o Redis virando cache derivado (ADR-004). "Atômico" vira verdade onde o dado vive.
2. `SETNX` com TTL de 48h mais `INCRBYFLOAT`, que é float para dinheiro. Um crash entre os dois comandos deixa a dedup registrada e o saldo não incrementado, o que é perda permanente. TTL expirado vezes reprocesso dá dupla contagem. Float perde centavos. A correção é um único SQL `ON CONFLICT`, com marker e efeito na mesma transação, mais `numeric(18,2)` (ADR-004).
3. Publisher-confirm no request path. O desenho dizia "201 imediato" e também "aguarda confirmação do RabbitMQ antes de considerar entregue", o que é contradição. Com o broker fora, o POST falharia com 5xx, violando o RNF#1. A correção é Transactional Outbox com relay: 201 no COMMIT e broker fora do caminho (ADR-003).

Eu não reescrevi o desenho: furei as três falhas, provei e endureci, reusando a intenção original, com CQRS, outbox declarado e DLQ declarada, e apenas tornei essas ideias verdadeiras.

## 3. Por que Outbox e relay (e não publisher-confirm)

O ponto crítico é onde o broker entra. Se ele está no caminho da requisição, uma indisponibilidade do broker vira indisponibilidade do lançamento, o que viola o RNF#1. O Outbox inverte isso: a verdade da publicação é uma linha no Postgres, gravada na mesma transação do lançamento, e o 201 sai no COMMIT. O broker pode estar fora, que as linhas PENDING acumulam e nada se perde. O relay, com `FOR UPDATE SKIP LOCKED`, publica depois e confirma por `delivery_tag`, nunca por lote, porque um `waitForConfirms()` bloquearia a thread e não sustentaria 50 req/s. O preço é a publicação at-least-once, que obriga um consumidor idempotente (ADR-004 e ADR-007).

## 4. Por que Postgres é a fonte de verdade (e o Redis é derivado)

Tentar ser "rápido e atômico" com Redis como verdade é a origem do problema, porque eviction e TTL podem derrubar a chave de dedup e permitir dupla contagem. A idempotência tem de viver onde o dado vive, no Postgres, com uma `PK (event_id, data)` no ledger `processed_event` que resolve sem lock distribuído. O RedLock é dispensável, porque uma constraint basta (Kleppmann). O Redis passa a ser cache read-through com single-flight: ele existe para acelerar a leitura, nunca para definir a verdade. Um miss volta ao Postgres, e a verdade nunca depende do cache.

## 5. Por que a conta mono-comerciante não derruba a solução

Mono-comerciante concentra todos os writes de um dia na mesma linha de saldo, o que gera row-lock e bloat. A correção não é distribuída: o ledger `processed_event` append-only, em que cada evento é uma linha que carrega `valor_signed`, com dedup e incremento juntos, e INSERTs concorrentes que não serializam, mais um rollup micro-batch que toca a linha de saldo uma vez por janela, e não por evento. É o mesmo princípio de sempre: um ponto quente, resolvido onde ele está, sem inventar um cluster.

## 6. Por que a mensageria virou uma tabela de parâmetros

"3 tentativas e backoff" com `nack{requeue=true}` é impossível de forma nativa numa classic queue: gira para sempre e o veneno nunca chega à DLQ. A solução não é um número mágico, porque `delivery-limit=3` descarta mensagem válida em failover, e sim uma taxonomia de erro: transiente na retry-queue com TTL e DLX e `x-retry-count`, e veneno com DLX imediato. A entrega confiável exige durabilidade explícita: `delivery_mode=2`, `mandatory` com `basic.return`, para que rota sem binding não suma em silêncio, `overflow=reject-publish` e `dead-letter-strategy=at-least-once`, sem o que o ledger perde na DLX (ADR-007).

## 7. Por que o local prova semântica, não o Alvo

O ambiente local, com RabbitMQ, Postgres e Redis em `docker-compose`, prova semântica e comportamento: que o SQL é idempotente, que o relay republica e que o veneno cai na DLQ. Ele não prova capacidade, alta disponibilidade, latência ou custo, porque o Aurora não é o Postgres local em ACU e failover, e o Amazon MQ não é o RabbitMQ local em limites. Por isso o resultado local vale como semântica, nunca como capacidade, e jamais como "Alvo atendido". Isso é honestidade de engenharia: não confundir um teste verde com uma garantia que ele não dá.

## 8. Por que a transição foi refeita (Fase 2 do Strangler)

O desenho fazia a Fase 2 com dual-write, gravando no Aurora e também atualizando o legado via evento, que é o mesmo erro que eu acabara de corrigir no núcleo. Seria incoerente exigir reconciliação no read-side e deixá-la ausente na transição. A correção reusa o mesmo outbox: o legado é atualizado por um adaptador, no padrão de ACL, como consumer idempotente, e o decommissioning, na Fase 4, só ocorre com paridade numérica medida, e não com "validação dos relatórios", e com rollback por troca de rota (ADR-011).

## 9. Por que o "diário" precisou de data de negócio

"Saldo diário" sem fuso é indefinido, porque 23:30 em São Paulo é outro dia em UTC. Pior: um evento consumido depois da virada, por outbox acumulada ou reprocesso de DLQ, cairia no dia errado se o "dia" fosse o de processamento. A correção é event-time: `data_negocio` explícita, definida na origem, como chave de agregação, com a coluna chamada `data` no banco, um nome por camada (ADR-012), fuso único declarado e watermark para o fechamento. O dia é dado do evento, imune a atraso (ADR-012). Sem isso, nem o fechamento diário nem a paridade da transição são determinísticos.

## 10. Por que nenhum nó novo para segurança e observabilidade

O desenho encarava "segurança para consumo" como infraestrutura, com WAF, ALB e TLS. O diferencial do PDF é critério de consumo, com escopo por chamador, ownership por recurso (BOLA), versão, quota e auditoria, mais dois itens que eu não posso omitir: a validação de JWT, com `iss`, `aud`, `exp` e `alg` (ADR-008), e a authz serviço a serviço e a credencial do broker, com users, vhosts e permissões, TLS server-side e `guest/guest` removido (ADR-008). Tudo isso cabe nos dois serviços: um middleware de JWT por convenção replicada nos dois serviços, com a pasta `security/` duplicada em `lancamentos` e `consolidado`, sem lib compartilhada, checagem de `merchant_id` na borda do domínio, auditoria no CloudTrail e quota na borda, com ALB e WAF. Um Authorization Service dedicado, ou um API Gateway como nó de aplicação, mudaria a topologia e a moldura de dois serviços sem novo componente, sem pagar, e por isso foi rejeitado (ADR-008). O API Gateway permanece apenas como opção documentada de borda, como serviço gerenciado e não como nó de aplicação. A mesma disciplina vale na observabilidade: CloudWatch, X-Ray e métricas próprias, sem licença de APM de terceiros, e o consumo desses serviços entra no ADR-013, com correlation id ponta a ponta, outbox lag e terminal, idade de backlog, broker high-water e `connection.blocked`, além de ACU e cold-cache (ADR-009). O diferencial de consumo está endereçado no desenho; a prova de segurança está no threat model e no ADR-008, e a de observabilidade, no ADR-009.

## 11. O que ficou pendente (e por quê)

- O `≤5%` é a régua do desafio: literal, com perda no consolidado a 50 req/s. A especificação mede, não reescreve. Sem o denominador, o teste de carga mede o alvo errado, porque a medição precede a prova.
- Custo, alta disponibilidade e licença: uma faixa estimada, com single em torno de US$20, active/standby em torno de US$430 e cluster em torno de US$714, mais região, momento e fonte e a linha de licença (ADR-013). Depende de RTO e RPO, que é decisão de negócio.
- Repositório público e lista canônica de documentos: a curadoria e a publicação são decisão minha.

Nada disso é esquecimento: é decisão minha ou prova pendente, cada um com responsável e gatilho. O que eu podia decidir com prova, decidi; o que é decisão minha, ficou marcado.

---

## Fontes

- O PDF `desafio-arquiteto-solucao-jun25.pdf`, o `VeretyCenario.docx` e o `DesfioVerety.drawio.xml`.
- Eric Evans (DDD, 2003), Chris Richardson (microservices.io: Outbox, Idempotent Consumer), Martin Fowler (CQRS, StranglerFig, MonolithFirst), Martin Kleppmann (DDIA 2017 e distributed locking 2016), Google SRE Book, AWS Well-Architected, RabbitMQ (Confirms, Quorum e Queue Length Limit), Michael Nygard (ADR, 2011) e Simon Brown (C4).
