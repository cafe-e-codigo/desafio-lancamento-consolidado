# Runbook: DLQ do consolidado (triagem, redrive idempotente e métricas)

Objetos: `caixa.lancamentos.exchange` (Topic), `consolidado-processamento-queue` (quorum), `consolidado-retry-queue` (`TTL+DLX`) e `caixa.dlx` que leva à `consolidado-processamento-dlq` (quorum, durável).
Regra de ouro: o broker garante at-least-once; o efeito idempotente vem do dedup durável, com `processed_event` de `PK (event_id, data)` e `ON CONFLICT (event_id, data) DO NOTHING`, em que marker e incremento em `valor_signed` ficam na mesma linha e na mesma transação, no ledger único.
O `saldo_diario` é escrito só pelo rollup, com um `UPDATE` por `{comerciante, data}` por janela, e nunca por evento (ver o `schema.sql`).
O redrive só é seguro porque o dedup é durável: redelivery da DLQ não duplica saldo (ADR-007).

> Os comandos valem em RabbitMQ local e Amazon MQ (semântica AMQP). Capacidade, alta disponibilidade e limites do Alvo não são provados localmente.

---

## 1. Sinais de alarme (o que dispara este runbook)

| Métrica | Limiar | Significado |
|---|---|---|
| `consolidado-processamento-dlq` `MessageCount` | maior que zero (alarme) | a mensagem foi a DLQ e precisa de triagem |
| `Consolidado OldestMessageAgeSeconds` | acima do bound do RF04 (a calibrar por medição) | consumidor parado ou atrasado, gerando backpressure |
| `Redelivered` e `x-delivery-count` (quorum) | taxa subindo sem parar | possível poison em retry, com a taxonomia falhando |
| memória e disco high-water do broker mais `connection.blocked` | qualquer evento | publisher bloqueado e relay acumulando outbox |
| `consolidado-retry-queue` `MessageCount` | crescendo de forma sustentada | transiente persistente, com dependência fora |
| `Outbox` `FAILED` (estado terminal) | maior que zero | `basic.return` ou nack esgotou as tentativas no relay |

---

## 2. Triagem (decidir a classe antes de qualquer ação)

```bash
# profundidade e amostra da DLQ (não consome, só inspeciona)
rabbitmqctl list_queues name messages messages_ready messages_unacknowledged
rabbitmqadmin get queue=consolidado-processamento-dlq count=5 ackmode=reject_requeue_true
```

1. Ler os headers: `x-death` com fila, count e reason, mais `x-first-death-reason` e `x-retry-count`.
2. Classificar, na mesma taxonomia do ADR-007:
   - VENENO, com schema inválido, unmarshal ou valor fora do contrato: corrigir o dado ou o produtor, e só depois o redrive, com replay idempotente.
   - TRANSIENTE esgotado, com a dependência fora além do teto: o redrive é seguro quando a dependência voltou.
   - DESCONHECIDO: não redrive às cegas, abra incidente.
3. Correlacionar o `event_id` com `processed_event` no Postgres: se já existe, o efeito já ocorreu e o redrive é no-op idempotente, o que é esperado; se não existe, o efeito nunca foi aplicado e o redrive é necessário.

> Nunca inspecione com `ackmode=ack_requeue_false` sem registro, porque um `get` consumidor remove a mensagem.

---

## 3. Redrive idempotente (o procedimento)

Opção A, com `shovel` nativo, que é o preferido: move da DLQ de volta à `consolidado-processamento-queue` sem reprocessar na aplicação, e a idempotência fica no consumidor.

```bash
# shovel dinâmico one-shot, da DLQ para a fila principal (AMQP URI do mesmo broker)
rabbitmqctl set_parameter shovel dlq-redrive \
 '{"src-uri":"amqp://<user>:<pass>@<host>/<vhost>",
   "src-queue":"consolidado-processamento-dlq",
   "dest-uri":"amqp://<user>:<pass>@<host>/<vhost>",
   "dest-queue":"consolidado-processamento-queue",
   "prefetch-count":10,"ack-mode":"on-confirm"}'
# ao terminar, remover o parâmetro para não ficar drenando continuamente:
rabbitmqctl clear_parameter shovel dlq-redrive
```

Opção B, com script administrativo, no espírito do 12-Factor de admin process: consome a DLQ e republica com o `event_id` inalterado na `consolidado-processamento-queue`, e o dedup durável absorve a duplicação.

- Pré-condições obrigatórias: `processed_event` durável no Postgres, e não TTL de cache; o redrive preserva o `event_id`; e o consumidor aplica marker e efeito na mesma transação.
- Idempotência garantida: a reentrega da mesma mensagem cai no `ON CONFLICT DO NOTHING`, com 0 linhas, então pula o efeito e faz ack, e o saldo não duplica (ADR-007).
- Proibido: redrive com `event_id` regenerado, que duplica, ou com dedup em TTL curto, porque o expirado recontaria.

---

## 4. Verificação pós-redrive (o que fechou)

1. `consolidado-processamento-dlq` com `MessageCount` igual a zero e estável.
2. Invariante de contagem:
   ```
   N(aceitos DISTINTOS por Idempotency-Key)
     == outbox(PENDING+FAILED) + COUNT(DISTINCT event_id) + DLQ + descartes_logados
   E: saldo_diario.valor == SUM(processed_event.valor_signed)     (SQL independente)
   ```
   com o termo de duplicação do broker, `x-delivery-count` e `redelivered`.
3. `Redelivered` volta a zero e não cresce o `x-delivery-count`.

---

## 5. Escalonamento e responsáveis

| Situação | Ação | Responsável |
|---|---|---|
| DLQ maior que zero por veneno | corrigir produtor e schema, e redrive após a correção | Backend |
| DLQ por transiente esgotado | confirmar a dependência e redrive com shovel | Mensageria |
| `Outbox FAILED` por rota ou `basic.return` | revisar binding e exchange, e republicar | Backend |
| broker em high-water ou `connection.blocked` | escalar o broker e limpar a fila, vendo FinOps | Plataforma |
| divergência no invariante | incidente de dados, que não é só mensageria | Dados |

---

## 6. Prevenções (para a DLQ não virar lixão)

- Taxonomia de erro no handler (ADR-007): veneno vai à DLQ na primeira, transiente vai para retry. Sem ela, a DLQ enche de mensagem recuperável e vira uma write-only DLQ.
- `x-delivery-limit` dimensionado para failovers e retries, evitando falso positivo em failover.
- Alarme acionável, e não só `MessageCount > 0`: este runbook é o par do alarme (Google SRE, sobre alarme sem ação).
- Retenção da DLQ menor ou igual à retenção do dedup, ou dedup durável, senão o replay recontaria.

---

## Fontes

- RabbitMQ: Dead Lettering e `x-death`; Quorum Queues e `x-delivery-limit`; plugin Shovel com `ack-mode=on-confirm`; Consumer Acknowledgements com `reject_requeue_true` no `get`.
- 12-Factor App: Admin processes, com o redrive como processo administrativo.
- Google SRE Book: Monitoring Distributed Systems e Being On-Call, com alarme acompanhado de runbook.
- ADR-007.
