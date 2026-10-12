# ADR-009: observabilidade, com correlação, alarmes de fluxo e auditoria

Status: Aceito

O escopo são dois serviços Java, RabbitMQ em quorum, Postgres, Redis e CloudWatch, sem nó novo de aplicação.

## Contexto

O desenho afirma os três pilares, métricas, logs e rastreamento distribuído, mas entrega só métricas CloudWatch e alarmes; o AWS X-Ray não é citado e o CloudTrail está ausente. Pior: os alarmes de tempo de resposta acima de 500 ms e de 5XX sobre requisições acima de 3% não medem os requisitos do desafio, que são 50 requisições por segundo e no máximo 5% de perda no consolidado. O fluxo introduz o outbox e esquece de vigiá-lo, e um outbox sem monitor de backlog é a falha silenciosa clássica. As correções canônicas retiram o `MessageCount` como detector suficiente, porque o outbox absorve o backlog e a fila pode ficar verde com o sistema travado, e abrem superfícies novas: o poison outbox, o flow control do broker de 1 GiB, com `connection.blocked`, e o efeito de manada de cache frio no Postgres e no ACU.

## Opções consideradas

**Opção A, manter as métricas AWS e os dois alarmes.**
- A favor: já está escrito.
- Contra: não instrumenta os requisitos, não tem trace nem correlação, não tem alarme de outbox e fica invisível sob as correções.

**Opção B, observabilidade completa com agente ou serviço dedicado, como OTel Collector e APM pago.**
- A favor: poder de correlação e amostragem, com dashboards ricos.
- Contra: é um nó novo com licença, o que contradiz a moldura e a meta de zero licenças, e é excesso para o escopo.

**Opção C, CloudWatch nativo mais X-Ray, métricas custom `Verety/*` e correlation id no envelope, sem nó novo (escolhida).**
- A favor: nó zero, paga-se só o que se usa, mede exatamente os requisitos, cobre as superfícies novas e o CloudTrail fecha a auditoria, reusada pelo ADR-008.
- Contra: exige definir os alarmes como contrato, com limiar e dono, e o custo de ingestão, log e trace entra no ADR-013.

## Decisão

Adoto a Opção C. O contrato de observabilidade é este.

1. Correlation id de ponta a ponta: o `correlation_id`, um UUID, é gerado no edge e propagado no envelope do evento, da escrita para o outbox, para a exchange, para o consumo e para o Redis e o log. Os logs estruturados em JSON carregam `correlation_id` e `event_id`, e o X-Ray traz traces atravessando os dois serviços e o broker, com subsegmento de publish e de consume.
2. Alarmes de fluxo do outbox: `Verety/Outbox OldestUnpublishedAgeSeconds`, que é a defasagem e torna o desacoplamento verificável, e o alarme de estado terminal `FAILED` ou de tentativas esgotadas, que é o poison outbox.
3. Idade do backlog do consolidado: `Verety/Consolidado OldestMessageAgeSeconds`, e não só `MessageCount`, porque é a idade que distingue um pico saudável de um travamento.
4. High-water e flow control do broker: alarmes de memória e de disco e de `connection.blocked`, porque num broker de 1 GiB o relay é bloqueado em silêncio.
5. ACU e cache frio: alarme de escala de ACU no Aurora Serverless v2 e de miss rate sob cold start, no efeito de manada. Miss não é staleness, e isso exige TTL e single-flight (ADR de dados).
6. Auditoria: CloudTrail habilitado para as chamadas de API, mais a trilha de decisão de autorização do ADR-008.
7. Medição dos requisitos: métricas de perda no consolidado com o denominador literal do desafio, que é consolidação sobre ingresso a 50 requisições por segundo, separadas do throttling de borda. O critério de no máximo 5% em si é do dono.

## Consequências

Positivas:
- Os alarmes passam a medir os requisitos, com o lag e a idade do outbox, o que fecha a lacuna de um alarme de 50 requisições por segundo que não existia.
- As três superfícies novas, poison outbox, flow control e cache frio, ganham detector, e o gate de verificação vira parte da operação, não opcional.

Negativas e mitigação:
- O custo da própria observabilidade, com ingestão de logs CloudWatch, traces X-Ray e data events do CloudTrail, entra no ADR-013; sem isso, observabilidade e FinOps ficam incoerentes.
- Exige limiares com dono e disciplina de `correlation_id` em todo publicador, senão o trace quebra; a mitigação é a biblioteca compartilhada de tracing e teste no harness.

## Fontes

- AWS X-Ray e AWS CloudTrail, para tracing e auditoria de API.
- AWS Well-Architected, Operational Excellence Pillar, para preparar e observar, e Security Pillar, para detecção e logging.
- Google SRE Book, Monitoring Distributed Systems, com os golden signals, e Handling Overload, com a ideia de manada.
- CNCF Observability Whitepaper, com métricas, logs e traces como pilares.
- Chris Richardson, sobre o Transactional Outbox e o monitor de backlog.
- RabbitMQ, Quorum Queues e Connection Blocked, sobre flow control.
