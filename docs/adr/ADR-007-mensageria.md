# ADR-007: mensageria e resiliência para entrega confiável

Status: Aceito

Quatro pontos de entrega ficaram pendentes no desenho e este ADR os fecha. O escopo é a fila do consolidado, com RabbitMQ local e Amazon MQ no alvo, dois serviços Java, mono-comerciante e sem nó novo. A garantia de entrega fica em at-least-once, e a prova local mostra a semântica, nunca a capacidade ou a alta disponibilidade.

## Contexto

O desenho declara fila durável, ack manual e DLQ, mas deixa quatro buracos de entrega.

1. Durabilidade do publish não declarada. Sem `delivery_mode=2`, sem exchange durável e sem `mandatory=true` mais `basic.return`, uma rota sem binding é descartada em silêncio, porque o publisher-confirm é um ack do broker e não da fila e não cobre isso. É vetor de perda, o oposto do RNF#1.
2. Overflow e backpressure do broker não fixados. O default `overflow=drop-head` descarta a mensagem mais antiga em silêncio, e numa quorum queue o default `dead-letter-strategy=at-most-once`, o que significa que o dead-lettering at-least-once só funciona com `overflow=reject-publish`. Para um ledger financeiro não pode haver drop-head nem at-most-once.
3. Taxonomia de erro ausente. O `nack{requeue=true}` numa classic queue gira para sempre em hot-loop e o veneno nunca chega à DLQ, enquanto o `x-delivery-limit` conta redelivery, o que inclui failover do broker, e pode matar uma mensagem válida depois de alguns distúrbios de infraestrutura. Transiente não é veneno.
4. Redrive operacional ausente. Uma DLQ com alarme e sem runbook é uma DLQ de escrita apenas, porque alarme sem ação não resolve. Falta o redrive idempotente, que só é seguro porque a deduplicação é durável, com `processed_event` e chave `(event_id, data)`.

## Opções consideradas

**Opção A, classic queue com retry-queues de TTL e DLX e contador no header `x-retry-count`.**
- A favor: controle explícito da tentativa na aplicação, porque o consumidor incrementa o `x-retry-count`, com backoff real por TTL; independente de recurso de quorum; e igual em local e no Amazon MQ.
- Contra: mais filas, com principal, retry e DLQ; a aplicação precisa republicar com o header; exige teto de tentativas no envelope; e classic não tem alta disponibilidade de quorum nativamente.

**Opção B, quorum queue com `x-delivery-limit` e DLX.**
- A favor: poison handling nativo, com `x-delivery-count`, e fila replicada por Raft, com alta disponibilidade real no alvo; o Amazon MQ suporta quorum.
- Contra: o contador é de redelivery e não de erro de negócio, então failover ou restart do broker incrementa e pode dead-letterar uma mensagem boa; o `x-delivery-limit` cru igual a 3 é perigoso; e o `reject-publish-dlx` de overflow não é suportado em quorum.

**Opção C, `nack{requeue=true}` com três tentativas e backoff, no estado atual do desenho.**
- A favor: nada a fazer.
- Contra: é impossível nativamente, com hot-loop infinito, DLQ nunca alcançada e CPU queimada. É só uma afirmação.

## Decisão

Adoto a Opção B como padrão, com a taxonomia de erro da Opção A embutida: quorum queue com `x-delivery-limit` dimensionado, e não cru igual a 3, mais uma retry-queue de TTL e DLX com `x-retry-count` para o transiente, escolhido por classe de erro. Os parâmetros canônicos da fila `consolidado-processamento-queue` são estes.

| Parâmetro | Valor canônico | Razão |
|---|---|---|
| `x-queue-type` | `quorum` | Poison handling nativo e alta disponibilidade por Raft; o Amazon MQ suporta. |
| `dead-letter-strategy` | `at-least-once` | O default de quorum é at-most-once, e um ledger não pode perder na DLX. |
| `overflow` | `reject-publish` | Pré-requisito do at-least-once, evita o drop-head silencioso e informa o publisher por `basic.nack`. |
| `x-max-length` e `x-max-length-bytes` | declarados, dimensionados ao backlog do caos | Backstop contra disco cheio e `connection.blocked`; quorum pode estourar uma mensagem ou mais, com folga declarada. |
| `x-dead-letter-exchange` e routing key | `caixa.dlx` para `consolidado-processamento-dlq` | DLQ durável para auditoria, alarme e redrive. |
| `x-delivery-limit` | dimensionado como retries mais failovers esperados, com o número declarado, e não fixo em 3 | Conta redelivery, o que inclui failover do broker, então 3 mata mensagem válida. |
| `prefetch` (`basic.qos`) | 10 | Janela in-flight limitada, com memória previsível, sem consumir a fila toda. |
| `delivery_mode` | 2, persistente | A mensagem sobrevive a restart do broker, com exchange e fila duráveis. |
| exchange e fila | duráveis | Quorum é durável por natureza, e a exchange `caixa.lancamentos.exchange` é declarada durável. |
| publish com `mandatory` | `mandatory=true` com `basic.return` | Rota não casada não some em silêncio, e o handler marca o outbox como falho ou em retry. |
| ack | manual, após o COMMIT | At-least-once: o ack só depois do efeito idempotente. |

### Taxonomia de erro

- Transiente, como banco indisponível, timeout, lock ou um blip do broker: não usar `requeue=true`. O consumidor faz `nack{requeue=false}` e a mensagem vai para a `consolidado-retry-queue`, com TTL e DLX e backoff progressivo, com `x-retry-count` incrementado no header pela aplicação. Ao esgotar o teto do envelope, vai para a DLQ.
- Veneno, como schema inválido, falha de unmarshal ou valor fora do contrato: rejeitar de imediato, com `requeue=false`, indo para `caixa.dlx` e a DLQ na primeira vez, sem consumir tentativa de retry.
- A classificação vive no handler, pelo tipo de exceção. Sem ela, quorum e retry-queue não distinguem veneno de transiente e a fila queima entrega válida.
- Sobre idempotência, o redrive da DLQ só é seguro por causa da deduplicação durável, com `processed_event`, chave `(event_id, data)` e `ON CONFLICT (event_id, data) DO NOTHING`, onde o marcador e o incremento `valor_signed` ficam na mesma linha e na mesma transação. O broker garante at-least-once, e o exactly-once é de efeito, no consumidor, nunca do relay, porque o confirm é durabilidade e não deduplicação.

## Consequências

Positivas:
- O RNF#1 fica preservado, porque não há `requeue=true` em hot-loop, o veneno chega à DLQ e o transiente não é descartado.
- Não há perda silenciosa, com `mandatory` e `basic.return`, `delivery_mode=2`, exchange e fila duráveis e `overflow=reject-publish`.
- A DLQ fica útil, porque o `dead-letter-strategy=at-least-once` garante que a mensagem não se perde entre filas na DLX.
- O comportamento é reproduzível localmente na comparação entre RabbitMQ e Amazon MQ, quanto à semântica, e não quanto à capacidade ou à alta disponibilidade.

Negativas e mitigação:
- O `x-delivery-limit` precisa de um número justificado por failovers e retries: se subdimensionado, mata mensagem válida em failover; se superdimensionado, atrasa o veneno. A mitigação é a dimensão declarada mais a taxonomia de erro.
- A retry-queue exige um contador na aplicação, o `x-retry-count`; sem ele, a retry-queue também gira, com TTL fixo e falha sempre.
- O quorum pode estourar uma mensagem ou mais no `reject-publish`, então o teto não é exato e o outbox precisa tolerar `basic.nack`.
- O Amazon MQ gerenciado restringe `rabbit.conf` e plugins e impõe limites de recurso por tipo, então parte do comportamento local não tem prova no alvo, quanto à capacidade e à alta disponibilidade.
- Sobre ordem, múltiplos consumidores, `prefetch` maior que 1 e redelivery não garantem ordem. O saldo é uma soma comutativa, mas o fecho diário exige event-time, watermark e backfill (ver o ADR de dados e calendário).
- Sobre custo, os alarmes de broker, como high-water e `connection.blocked`, e a retenção da DLQ entram no FinOps.

## Vínculos

- ADR-005: `Idempotency-Key` para `event_id` determinístico, que é a chave da deduplicação durável e a pré-condição do redrive.
- ADR-008: contrato de consumo, com autenticação e TLS do broker, e o usuário `guest` fora do alvo.
- A comparação entre RabbitMQ local e Amazon MQ, quanto à semântica.
- O `runbook-dlq.md`, com triagem, redrive idempotente e métricas de redelivery e idade.
- O `topologia/mensageria.drawio`, que troca os vínculos de `caixa.lancamentos.exchange` para as filas, retry e DLQ.

## Fontes

- RabbitMQ, na documentação oficial: Publisher Confirms and Consumer Acknowledgements, com `waitForConfirms` contra listener e `mandatory` com `basic.return`; Reliability Guide, com at-least-once e persistência; Queue Length Limit, com `x-max-length`, `overflow=drop-head` descartando o mais antigo e `reject-publish` dando `basic.nack`; Quorum Queues, com `x-delivery-limit`, `x-delivery-count`, `dead-letter-strategy` default at-most-once, at-least-once exigindo `overflow=reject-publish` e overshoot de uma mensagem ou mais; Time-To-Live and Expiration, para a retry-queue; e Connection Blocking e Resource Alarms, com `connection.blocked`.
- AWS, na documentação do Amazon MQ for RabbitMQ: deployment options, suporte a quorum queues e limites de recurso.
- microservices.io, de Chris Richardson: Transactional Outbox e Idempotent Consumer.
- AWS Well-Architected, Reliability Pillar REL05: retry com backoff, idempotência e teste de falha.
- Google SRE Book: Addressing Cascading Failures e Monitoring Distributed Systems, sobre alarme sem ação.
