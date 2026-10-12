# ADR-003: outbox transacional com relay assíncrono (SKIP LOCKED)

Status: Aceito

Relaciona os ADR-005 (contrato de escrita), ADR-008 (fila e DLQ), o RF04 e o RNF#1 do desafio.

## Contexto

O desenho declara o padrão Transactional Outbox, mas o fluxo que ele descreve é um dual-write: grava o evento na base e envia a mensagem, responde 201 na hora, e em outro ponto espera a confirmação do RabbitMQ antes de considerar o evento entregue. No diagrama, a ligação entre o serviço e a fila é direta, sem uma tabela de outbox e sem relay.

A consequência é mecânica. Com o broker fora, o POST ou falha com 5xx, o que viola o RNF#1, ou perde o evento num crash entre o insert e a publicação. O invariante de reconciliação exige que o evento nunca se perca.

Os requisitos aplicáveis são o RNF#1, em que a indisponibilidade do consolidado ou do broker não pode ser a indisponibilidade do lançamento, e o RF04, que pede saldo em tempo real ou com latência imperceptível, mas que no desenho é só uma afirmação, sem número.

## Opções consideradas

**Opção A, publicar direto na request com publisher-confirm síncrono.**
- A favor: simples, com latência de leitura de saldo menor.
- Contra: coloca o broker no caminho crítico do POST. Broker fora dá 5xx e viola o RNF#1, e um crash depois do insert e antes do publish perde o evento, que é o dual-write de novo. Rejeitada.

**Opção B, outbox transacional com relay assíncrono (escolhida).**
- A favor: o 201 sai no commit do banco, o broker não está no caminho da request, e com o broker fora as linhas pendentes acumulam sem perder nada. A reconciliação fica explícita.
- Contra: existe latência de propagação, porque o GET pode ver o saldo só depois que o relay e o consumidor processarem, o que exige um bound de lag para o RF04. A publicação vira at-least-once e obriga um consumidor idempotente (ADR-004).

**Opção C, CDC pelo log, com Debezium lendo o WAL.**
- A favor: não há polling e a ordenação é natural.
- Contra: acrescenta um componente operacional (Connect ou Kafka) e custo de plataforma para um requisito de 50 requisições por segundo, além de sair do escopo da moldura, que é mono-comerciante em docker-compose. Rejeitada para este escopo e registrada como evolução.

## Decisão

Adoto o outbox transacional com relay assíncrono, no mesmo Postgres do serviço de lançamentos.

1. A escrita acontece na mesma transação ACID:

```sql
BEGIN;
  INSERT INTO lancamentos.lancamento (transaction_id, comerciante_id, data, valor, tipo, ...) VALUES (...);
  INSERT INTO lancamentos.outbox (event_id, aggregate_id, event_type, payload)
         VALUES ($event_id, $lancamento_id, 'LancamentoCriado', $payload_json);
COMMIT;   -- só então a API responde 201 Created, com o Location do recurso
```

2. O relay é um processo interno do serviço de lançamentos, agendado, com concorrência sem lock global:

```sql
SELECT id, event_id, payload FROM lancamentos.outbox
 WHERE status = 'PENDING' AND next_attempt <= now()
 ORDER BY id LIMIT $batch
 FOR UPDATE SKIP LOCKED;
```

Ele publica no topic exchange com `mandatory=true`, `delivery_mode=2` e publisher confirm por `delivery_tag`; ao confirmar, faz `UPDATE outbox SET status='PUBLISHED', published_at=now()`.

3. Estados do item de outbox: `PENDING` vai para `PUBLISHED`; em falha, incrementa `attempts` e agenda `next_attempt` com backoff, permanecendo `PENDING`; depois de N tentativas (default 10) passa a `FAILED`, que é terminal e dispara alarme. Só `PENDING` é republicado. `FAILED` não entra em hot-loop e vai para triagem, com o passo a passo no `README.md`.

4. A confirmação usa confirms assíncronos, com um mapa de correlação por `delivery_tag`, e não `waitForConfirms` em lote, porque ele bloqueia a thread e o dreno fica em torno de 1/RTT, o que a 100 ms dá perto de 10 por segundo, insuficiente para 50 requisições por segundo. Nunca se marca `PUBLISHED` a partir de `multiple=true` sem saber qual tag confirmou, porque uma falha no meio do lote seria perda silenciosa.

5. No desenho, o outbox entra no C4 de nível 3 como tabela do Postgres mais o componente `OutboxRelay` interno ao container de lançamentos, sem virar um container novo no nível 2.

### Bound de lag do RF04

O lag de ponta a ponta se decompõe em tempo de poll do relay, tempo de publicação no broker, tempo de consumo, commit no `processed_event` e rollup:

```
lag = poll_interval(relay) + t_publish(broker) + t_consume + t_commit(processed_event) + t_rollup
```

A decisão é `poll_interval` de no máximo 200 ms, com SLO-alvo de lag p95 de no máximo 2 s no caminho feliz, condicionado a que a taxa de dreno seja maior ou igual à taxa de chegada e o backlog fique perto de zero.

- O critério de 50 requisições por segundo com no máximo 5% de perda no consolidado não pertence a este ADR: ele é do dono.
- Com o broker fora, o lag é ilimitado por definição, já que o outbox acumula, e isso é correto, porque prevalece o RNF#1 e nada se perde. O RF04 não é um SLO incondicional, e o limite superior do lag é verificado pelo teste de carga, não afirmado.
- As métricas obrigatórias com alarme são a idade do item de outbox mais antigo e a contagem de `PENDING` e `FAILED`.

## Consequências

Positivas:
- O RNF#1 é satisfeito por construção: broker fora ou consolidado fora não afetam o POST, que responde 201 no commit.
- Zero perda de evento aceito: a linha de outbox é a fonte da verdade da publicação, e a reconciliação fecha.
- Há backoff real no relay, que o RabbitMQ nativo não tem, com estado terminal explícito.
- O relay de legado da transição reutiliza o mesmo padrão.

Negativas:
- A publicação at-least-once obriga o consumidor idempotente (ADR-004). Duplicata é esperada, não é bug.
- Existe o lag de propagação do RF04, que é um SLO condicional, verificado e não garantido sob caos.
- Há custo de bloat e de expurgo da tabela de outbox, com política de archival das linhas já publicadas.
- É mais um processo para operar, o relay, mais o alarme de idade e de estado terminal.

A verificação é esta: com o broker fora, o POST responde 201 e o outbox fica pendente; depois do restart do broker, o pendente vira publicado, com zero perda; e sob contenção na conta quente, o comportamento se mantém.
