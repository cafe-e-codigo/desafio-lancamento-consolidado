# ADR-010: dados, com retenção, particionamento, connection pool e PII

Status: Aceito, com a parte de retenção legal condicionada à decisão do dono

Relaciona os ADR-006 (read-side), ADR-009 (observabilidade) e ADR-013 (FinOps). A moldura é mono-comerciante, com Postgres ou Aurora e dados financeiros (RF02).

## Contexto

Nenhum insumo trata o ciclo de vida dos dados. O extrato do cenário não contém um `CREATE TABLE`, e por isso não há política de retenção, de particionamento nem de PII declarada. As correções canônicas criam tabelas de crescimento monotônico.

| Tabela | Crescimento | Risco sem política |
|---|---|---|
| `lancamentos.lancamento` | uma linha por requisição aceita, de forma permanente | o índice `(comerciante_id, data)` e a tabela incham, e backup e PITR ficam caros |
| `lancamentos.outbox` | uma linha por evento, e a linha publicada nunca sai | bloat do relay, e o `SKIP LOCKED` fica mais lento, porque o índice parcial cobre só o pendente, mas o heap cresce |
| `consolidado.processed_event` | uma linha por evento, para sempre, porque é a deduplicação durável e o incremento append-only, com o marcador e o `valor_signed` na mesma linha, e não pode expirar como o `SETNX` com TTL de 48 h fazia | a chave de deduplicação e o índice viram o maior objeto do banco |
| `consolidado.saldo_diario` | uma linha por `{comerciante, data}` | pequeno, que é bom |

Além disso, falta o endpoint de leitura, entre o writer e a read replica, porque se o read-through ler a réplica, o lag da réplica envenena o cache por todo o TTL, e falta o connection pool, porque são 50 requisições por segundo com misses e writes em Aurora Serverless v2 sem pool.

Dados de um fluxo de caixa podem conter PII, com a identificação do comerciante e eventualmente do pagador. O desafio pede critérios de segurança de integração, e não há campo sensível declarado nos insumos, então a política de PII é defensiva e não um over-build.

## Opções consideradas

**Opção A, não fazer nada.**
- A favor: zero esforço.
- Contra: tabelas de crescimento ilimitado; a deduplicação expira se alguém tentar resolver com TTL, o que reintroduz a dupla contagem; o custo de storage e PITR cresce sem teto; e a PII fica sem redação. Rejeitada.

**Opção B, TTL ou expurgo agressivo no `processed_event`, espelhando o TTL de 48 h do desenho.**
- A favor: tabela pequena.
- Contra: mata a idempotência durável, porque é o mesmo defeito de `SETNX` com TTL de 48 h contra replay de DLQ, que dá dupla contagem (ADR-004). Rejeitada com firmeza.

**Opção C, retenção em camadas, particionamento por tempo, arquivamento frio, pool e redação (escolhida).**
- A favor: a deduplicação permanece, porque a retenção do `processed_event` é maior ou igual à janela máxima de reprocesso; as partições velhas vão para arquivo frio em S3 ou cold store; o índice do relay fica coberto por índice parcial; o pool é explícito; e a PII é redigida.
- Contra: exige jobs de manutenção, um endpoint de leitura decidido e uma decisão do dono sobre a retenção legal.

**Opção D, `processed_event` como tabela particionada por hash e permanente, com bloom de idempotência.**
- A favor: deduplicação para sempre.
- Contra: complexidade alta, porque o particionamento por hash não casa com a query por data, e é excesso para o escopo. Rejeitada e registrada como evolução.

## Decisão

Adoto a Opção C.

1. Particionamento por tempo, mensal, em `lancamento` e `processed_event`.
   - `PARTITION BY RANGE (data)`, com partições mensais, e a chave primária e a `UNIQUE` incluem a chave de particionamento, como o Postgres exige.
   - A deduplicação é preservada: a `UNIQUE (event_id, data)` inclui a chave de partição `data`, que é exigência do Postgres para `UNIQUE` e `PK` de tabela particionada. Logo o `ON CONFLICT` tem de ser `(event_id, data)` e não `event_id` isolado, que não é indexável como único. O SQL canônico usa `(event_id, data)`.
   - Um `CREATE INDEX ... ON processed_event (comerciante_id, data)` por partição.
2. Retenção em camadas, que nunca apaga a janela de reprocesso.
   - Quente, no Postgres: os últimos N meses, por exemplo 24, e o `processed_event` precisa cobrir no mínimo a idade máxima da DLQ mais o redrive, senão o replay duplica.
   - Frio, no cold store do Aurora ou em export para S3 com PITR: as partições mais antigas saem por `DETACH PARTITION` e vão por `pg_dump` ou `COPY` para S3 em Parquet, o que mantém a auditoria sem inflar o hot set.
   - `outbox`: expurgo das linhas publicadas após uma retenção curta, por exemplo 7 dias, porque o evento de negócio já está em `lancamento` e em `processed_event` e o outbox é transporte.
   - `processed_event`, o ledger append-only: é a única fonte do rollup, e não há tabela de staging de incremento, porque o marcador de deduplicação e o `valor_signed` são a mesma linha. O expurgo e o archival não podem cair abaixo de `max(processado_em)` menos a janela de reprocesso, que é a DLQ mais o redrive, senão o replay duplica.
   - O dono da decisão de prazo legal é o dono, porque a contabilidade pode exigir 5 anos.
3. Endpoint de leitura como o writer primário, e não a réplica.
   - A justificativa é que o read-through deve ler o valor pós-commit, e a réplica tem lag e envenenaria o cache, com uma defasagem que o TTL não corrige. O cold store serve o arquivamento, não o GET quente.
   - Se um dia a escala exigir réplica para o GET, exige-se uma réplica com `hot_standby_feedback` e sem lag perceptível, mais um bound de lag e invalidar o cache em vez de confiar no TTL, o que fica registrado como evolução.
4. Connection pool explícito, com PgBouncer em transaction mode entre os serviços e o Aurora.
   - São 50 requisições por segundo por dois serviços, com efeito de manada amortecido por single-flight (ADR-006), e o pool evita esgotar conexões do Aurora Serverless v2 no ramp de ACU.
   - `default_pool_size` e `max_client_conn` são dimensionados por aferição, e não por chute. O Aurora Serverless v2 usa `max_connections` proporcional à ACU, que é um ponto de atenção.
5. PII e redação, de forma defensiva.
   - Minimização: não armazenar dados do pagador além do necessário ao lançamento, e o `payload` do outbox não carrega PII além do `merchant_id`.
   - Classificação: o `merchant_id` é dado de negócio e não sensível por si; se algum campo de identificação entrar, usa cifra em repouso com KMS, já citado, mais redação em logs, com máscara no log estruturado do ADR-008 e do ADR-009.
   - Rastreio: usar `correlation_id` e `event_id` em vez de PII nos logs (ADR-009).
   - Não há campo de PII declarado nos insumos, então a política é preventiva; se o dono declarar campos sensíveis, a classificação e a criptografia são reavaliadas.

A equivalência entre local e alvo é esta: o local prova a semântica de particionamento, índice e pool, com Postgres e PgBouncer open-source, e não prova a capacidade do Aurora Serverless v2, com ACU e `max_connections` dinâmico, nem a retenção e o custo de storage.

## Consequências

Positivas:
- A deduplicação durável é preservada, porque a retenção nunca cai abaixo da janela de reprocesso, e o erro do TTL de 48 h não se repete.
- O hot set fica enxuto, com as partições velhas indo para o frio, e o índice parcial de relay, com `WHERE status='PENDING'`, mantém o relay rápido e o bloat contido.
- A leitura fica consistente, porque o GET lê o primário pós-commit e o cache não é envenenado pela réplica.
- O pool evita contenção de conexões no pico de 50 requisições por segundo, com no máximo 5% de perda no consolidado, e a PII é minimizada e redigida por padrão.

Negativas e mitigação:
- Os jobs de manutenção, com detach, arquivamento e expurgo, são componentes observáveis novos; sem alarme, o arquivamento silencioso pode falhar.
- A retenção legal é decisão do dono, e o prazo contábil pode ser maior que 24 meses; o mínimo técnico, que é a janela de reprocesso, é uma decisão de engenharia, e o máximo legal é do dono.
- O particionamento exige `UNIQUE` incluindo a chave de partição, o que ajusta o DDL do ADR-004, e o evento fica sempre na data de negócio, que é estável.
- O PgBouncer em transaction mode proíbe sessão e `prepared statements` por sessão, então a aplicação usa statements sem sessão, o que fica declarado no README do produto.

## Pendências, com dono e gatilho

| Pendência | Dono | Gatilho |
|---|---|---|
| Prazo de retenção legal, contábil e fiscal, acima do mínimo técnico | dono | antes do arquivamento em produção |
| Endpoint de réplica para o GET, se a escala exigir | engenharia | se a aferição mostrar gargalo do primário |
| Campos de PII declarados | dono | se o dono declarar campos sensíveis |

## Fontes

- PostgreSQL, na documentação oficial: Table Partitioning, com `PARTITION BY RANGE`, `DETACH PARTITION` e a restrição de `UNIQUE`; Routine Vacuuming; e Bloat.
- PgBouncer, na documentação oficial: transaction e session pooling, e `max_client_conn`.
- AWS: Aurora Serverless v2, com `max_connections` proporcional à ACU, e as melhores práticas de Aurora PostgreSQL para pooling e connection storms.
- AWS Well-Architected: Reliability, com gestão de dados, backup e PITR, e Cost Optimization, com ciclo de vida e storage tiering.
- Google SRE Book, sobre Data Integrity.
- OWASP, sobre Cryptographic Storage, com dados em repouso e minimização.
- Michael Nygard, Documenting Architecture Decisions (2011).
