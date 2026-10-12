# Observabilidade de dados: métricas, limiares e responsáveis

Régua: todo limiar tem um responsável; sem valor plausível, o limiar fica a calibrar por medição até o harness medir. A fonte é o banco, o Redis e o broker, lidos por fora da aplicação, e nunca um contador interno.
Relaciona: `ADR-006` (read-side), `ADR-010` (retenção e pool) e `ADR-009` (observabilidade geral).

---

## Contexto

O meu primeiro desenho cobre ECS, MQ, ALB e ElastiCache, mas não tem uma única métrica de banco: nem `connections`, nem bloat e vacuum, nem `replica lag`, nem hit e miss real do cache, nem lag do outbox, nem delta de reconciliação. Os alarmes que existiam, com `TargetResponseTime > 500ms` e `5XX/RequestCount > 3%`, não medem os RNFs nem este eixo. As correções canônicas criaram três superfícies novas que exigem detector: thundering herd no cold cache, hot-row e rollup no saldo, e poison-outbox.

Fonte externa: o Google SRE Book, com os golden signals de latência, tráfego, erros e saturação; o AWS Well-Architected, em Operational Excellence e Reliability, com as métricas de banco como saturação; e as estatísticas do PostgreSQL (`pg_stat_*`) e o `INFO` do Redis.

---

## Catálogo de métricas de dados

| # | Métrica (namespace `Verety/`) | Fonte independente (fora da app) | O que mede | Alarme e limiar (a calibrar por medição) | Responsável |
|---|---|---|---|---|---|
| DB-1 | `DB ConnectionUtilization` (uso sobre max) | Aurora e CloudWatch `DatabaseConnections` mais `max_connections` | saturação de pool a 50 req/s e com misses | aviso em 70%, crítico em 85% | Dados |
| DB-2 | `PgBouncer PoolWaitTime` e `cl_waiting` | métricas do PgBouncer (`SHOW POOLS`) | contenção de conexões sob pico | `cl_waiting > 0` por mais de 30s, com aviso | Dados |
| DB-3 | `DeadTuplesRate` (`n_dead_tup` por minuto) | `pg_stat_user_tables` | bloat do hot-row e rollup (ADR-006) | crescimento contínuo sem queda pós-vacuum, crítico | Dados |
| DB-4 | `AutovacuumLag` (oldest `xmin` e `last_autovacuum`) | `pg_stat_user_tables` e `pg_stat_activity` | vacuum que não acompanha os dead tuples (bloat) | `last_autovacuum` acima de 1h com dead tuples altos, aviso | Dados |
| DB-5 | `RollupLagSeconds` (now menos `max(processado_em)` do consolidado) | SQL sobre `processed_event` e `rollup_controle` | bound de leitura (RF04 e ADR-006) | p95 menor ou igual a 2 s, a calibrar por medição; crítico acima de 10 s | Dados |
| DB-6 | `RollupWatermark` (último `ultimo_processado_em`) | `consolidado.rollup_controle` | job de rollup travado em silêncio | parado por mais de duas vezes o `rollup_interval`, crítico | Dados |
| DB-7 | `ReconciliacaoDelta` (linhas divergentes) | SQL do invariante (ADR-006) | corretude monetária em runtime | qualquer linha diferente de zero, crítico (P1) | Dados |
| DB-8 | `Cache HitRate` igual a `keyspace_hits` sobre hits mais misses | Redis `INFO stats` | hit e miss real, o que derruba a ideia de 100% de hit | aviso abaixo de 60%, a calibrar por medição; não prometer piso | Dados |
| DB-9 | `CacheMissStorm` (misses por segundo por chave) | Redis `INFO` e log de single-flight | thundering herd em cold cache e failover | pico de miss concorrente acima de N, aviso | Dados |
| DB-10 | `CacheEvictedKeys` e `EvictedKeysRate` | Redis `INFO stats` em `evicted_keys` e `maxmemory_policy` | eviction derrubando saldo (staleness) | `evicted_keys > 0` com `maxmemory` atingido, aviso | Dados |
| DB-11 | `OutboxOldestAgeSeconds` | SQL sobre `outbox` com `status='PENDING'` | defasagem do relay (ADR-003 e RF04) | p95 menor ou igual a 2 s, a calibrar por medição; crítico acima de 10 s | Mensageria |
| DB-12 | `OutboxTerminalCount` (`FAILED`) | SQL sobre `outbox` com `status='FAILED'` | poison-outbox no estado terminal (ADR-003) | qualquer incremento, crítico | Mensageria |
| DB-13 | `SaldoDiarioRowCount` e `ProcessedEventRowCount` | `pg_class.reltuples` e `count(*)` | crescimento e retenção (ADR-010) | desvio do modelo de retenção, aviso | Dados |
| DB-14 | `PartitionAgeOldestHot` (mês mais antigo no hot set) | `pg_partition_tree` e catálogo | job de archival que não roda | partição além da janela, aviso | Dados |
| DB-15 | `ReplicaReadLag` (se o GET usar réplica) | Aurora `AuroraReplicaLag` | envenenamento de cache por lag (ADR-010) | hoje desligado, com GET no primário; se ligar, p95 menor ou igual ao bound | Dados |
| DB-16 | `DescarteLogadoRate` | SQL sobre `descarte_logado` por motivo | descarte não silencioso | pico de `POISON` ou `SEM_DESTINO`, aviso | Dados |

> Proibido como termômetro: qualquer contador interno da aplicação. Toda métrica acima sai do banco, do Redis e do broker, lidos por ferramenta externa (`psql`, `redis-cli`, `SHOW POOLS`, CloudWatch).

---

## Invariante de reconciliação (alarme de corretude, DB-7)

Rodar como job independente, fora do request path, com periodicidade a calibrar por medição, de 1 min:

```sql
-- delta de corretude: deve retornar 0 linhas; qualquer linha vira alarme P1
SELECT s.comerciante_id, s.data, s.valor AS projetado,
       COALESCE(SUM(p.valor_signed),0) AS recomputado
  FROM consolidado.saldo_diario s
  LEFT JOIN consolidado.processed_event p
         ON p.comerciante_id = s.comerciante_id AND p.data = s.data
 GROUP BY s.comerciante_id, s.data, s.valor
HAVING s.valor <> COALESCE(SUM(p.valor_signed),0);
```

Termo do invariante de fechamento, lido por fora da aplicação:
```
N(aceitos DISTINTOS por Idempotency-Key)
  == outbox(PENDING+FAILED) + COUNT(DISTINCT event_id) + DLQ + descartes_logados
E: saldo_diario.valor == SUM(processed_event.valor_signed)
```
`COUNT(DISTINCT event_id)`, nunca a contagem crua de entregas, que sofre duplicação do relay, nem `SCAN idempotency:*`, porque TTL e eviction mentem.

---

## Alarmes derivados do eixo (fecham as 3 superfícies novas)

| Superfície (contra-exemplo) | Alarme | Ação |
|---|---|---|
| thundering herd (cold cache) | `DB-9` mais `DB-3` e `DB-1` correlacionados | verificar single-flight (ADR-006) e ACU do Aurora |
| hot-row e bloat | `DB-3` mais `DB-4` e `DB-5` | rollup micro-batch, com 1 UPDATE por grupo; se o DeadTuplesRate sobe, o rollup não está agregando |
| poison-outbox (ADR-003) | `DB-12` | triagem do runbook de DLQ (`runbook-dlq.md`) |

---

## Limiares a calibrar (responsável + gatilho)

| Item | Responsável | Gatilho |
|---|---|---|
| Limiares de DB-5, DB-8, DB-9 e DB-11, confirmados por medição | Dados e Mensageria (harness) | confirmação por medição com o harness do repositório |
| DB-15 (réplica), ligar só se a escala exigir | Dados | se DB-1 ou DB-2 saturar o primário |
| Custo de ingestão destas métricas | Segurança | ADR-013, já endereçado |

---

## Fontes

Google SRE Book: Monitoring Distributed Systems (golden signals e saturação), Service Level Objectives e Handling Overload. PostgreSQL docs: Monitoring (`pg_stat_user_tables`, `pg_stat_activity`, `pg_class`), Routine Vacuuming e Table Partitioning. Redis docs: `INFO stats` (`keyspace_hits`, `misses`, `evicted_keys`) e Key eviction. PgBouncer docs: `SHOW POOLS`. AWS: CloudWatch (`DatabaseConnections`), Aurora Serverless v2 (ACU) e `AuroraReplicaLag`. Chris Richardson: Transactional Outbox, no monitor de backlog. Nygard: ADR.
