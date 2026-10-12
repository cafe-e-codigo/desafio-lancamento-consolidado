# ADR-013: FinOps, custo de infraestrutura e licenças, e faixa de alta disponibilidade do broker

Status: Aceito, com a escolha de faixa condicionada à decisão do dono

## Contexto

O desafio, nos diferenciais, exige uma estimativa de custos com infraestrutura e licenças. O desenho soma alguns valores e chega a algo em torno de US$230, sem região, sem data e sem fonte, o que é irreproduzível, e afirma eliminar integralmente os custos com licenças, o que o próprio desenho contradiz ao citar uma ferramenta de plantão que tem licença paga. Ficam fora da tabela o WAF, por ACL, regra e requisição, o NAT Gateway, o Secrets Manager, o KMS, o CloudTrail e agora também o CloudWatch logs e o X-Ray, que o ADR-009 exige. O desenho cria autoscaling com teto de capacidade sem um guardrail de custo, e um orçamento com alerta em 80% e 100% é reativo. Por fim, o desenho rotula o broker como instância pequena multi-AZ, o que é falso, porque a instância pequena é single-instance e não suporta cluster. As faixas plausíveis são três, e eu estimo por ordem de grandeza: single sem alta disponibilidade, da ordem de algumas dezenas de dólares por mês, active/standby da ordem de algumas centenas, e cluster de três nós, da ordem de várias centenas. São estimativas, não números aferidos.

## Opções consideradas

**Opção A, manter o total baixo, com broker single-instance e a frase de zero licenças.**
- A favor: um número baixo e apresentável.
- Contra: não tem prova, porque falta região, data e fonte; omite a licença que o desafio exige; o rótulo multi-AZ é falso; e contradiz o SLA de 99,99%, porque single-instance não sustenta esse SLA e falha em minutos.

**Opção B, faixa de custo reproduzível, decisão de alta disponibilidade por RTO e RPO, e nó de guardrail (escolhida).**
- A favor: é reproduzível, com a calculadora de preços da AWS e com região e data; atende ao infra e licenças; o SLA passa a ser coerente com a faixa comprada; e o guardrail evita pico de gasto.
- Contra: exige uma decisão de RTO e RPO, que hoje não existe, e mais linhas na tabela.

**Opção C, sobredimensionar o cluster de três nós por padrão.**
- A favor: alta disponibilidade máxima.
- Contra: custa na casa das várias centenas de dólares por mês num desafio mono-comerciante, o que é desperdício, e não é o gargalo do sistema, porque o gargalo aferido é a contenção do `saldo_diario`, e não a alta disponibilidade do broker.

## Decisão

Adoto a Opção B.

1. Fonte única de custo: um snapshot da calculadora de preços da AWS, com região e data anexadas. O valor total deixa de ser um número solto e vira o piso de uma tabela versionada.
2. Linha de licença obrigatória: a ferramenta de plantão, e quaisquer outras, como runners pagos de integração contínua, entram explicitamente na tabela, e a frase de zero licenças é riscada.
3. Linhas faltantes: somar o WAF, o NAT Gateway, o Secrets Manager, o KMS, o CloudTrail e o custo da observabilidade do ADR-009, com os logs do CloudWatch, os traces do X-Ray e os data events do CloudTrail.
4. Faixa de alta disponibilidade do broker, com RTO e RPO: escolher entre single, sem alta disponibilidade, da ordem de algumas dezenas de dólares, active/standby da ordem de algumas centenas, e cluster da ordem de várias centenas, conforme um ADR de RTO e RPO. Esse ADR rebaixa o SLA de 99,99% ao que a faixa compra, ou sobe a faixa para sustentá-lo. Enquanto não houver decisão, o custo do broker é faixa, e não valor.
5. Guardrail de escala: um teto orçamentário e um alarme de gasto ligados ao teto de capacidade do autoscaling, e não só o orçamento reativo em 80% e 100%.
6. Declaração de não equivalência: custo e capacidade não são aferidos localmente, e o número local não valida o alvo.

A faixa de alta disponibilidade e o RTO e RPO ficam pendentes de ratificação do dono. Sem isso, o item fica pendente, com dono o próprio dono e gatilho no fecho da fase.

## Consequências

Positivas:
- O custo fica reproduzível e auditável, com região, data e fonte, e com as licenças, que são exigência literal do desafio.
- Há coerência entre os diferenciais, porque a observabilidade (ADR-009) passa a ter custo contabilizado e o SLA deixa de contradizer a infraestrutura.
- O guardrail protege contra o pico de gasto do autoscale.

Negativas e mitigação:
- O número final sobe acima do valor inicial e vira faixa, e transparência vale mais do que um número bonito.
- Depende de uma decisão de negócio, sobre o RTO e o RPO, que a engenharia não decide e que sobe ao dono; a mitigação é publicar as três faixas com prós e contras e pré-preçar a escolhida.

## Fontes

- A calculadora de preços da AWS, como fonte do custo reproduzível.
- AWS Well-Architected, Cost Optimization Pillar, sobre governança, atribuição e análise de custo e guardrails.
- AWS Budgets, que é reativo, contra o teto de escala, que é preventivo.
- O framework da FinOps Foundation, com informar, otimizar e operar.
- AWS, sobre as opções de implantação do Amazon MQ for RabbitMQ, com single de uma zona contra cluster de três nós, e a instância pequena sem cluster.
- Michael Nygard, Documenting Architecture Decisions (2011).
