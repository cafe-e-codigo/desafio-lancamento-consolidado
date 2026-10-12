-- =============================================================================
-- Verety: Flyway V1, esquema `consolidado` (READ-SIDE / projeção)
-- Fonte canônica do DDL: docs/schema.sql. Adaptada para migração versionada.
-- Doutrina: ADR-004 (idempotência 1 SQL / ledger append-only) ·
--           ADR-006 (read-side: saldo escrito SÓ pelo rollup) · ADR-010 (retenção/partição).
-- Ledger ÚNICO: processed_event carrega o MARKER de dedup E o valor_signed, MESMA linha.
-- NÃO existe tabela `saldo_incremento`. Dinheiro SEMPRE numeric(18,2).
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS consolidado;

-- -----------------------------------------------------------------------------
-- processed_event: LEDGER DURÁVEL = dedup idempotente (substitui SETNX+TTL 48h).
-- 1 linha por evento, PARA SEMPRE (retenção ≥ janela de reprocesso: ADR-010).
-- Funde o "marker de dedup" + o "incremento append-only" (ADR-004): o marcador
-- CARREGA valor_signed. SEM TTL (TTL reintroduziria dupla contagem).
-- PARTITION BY RANGE (data): retenção/archival; por isso `data` compõe a PK.
-- -----------------------------------------------------------------------------
CREATE TABLE consolidado.processed_event (
    event_id       uuid          NOT NULL,
    comerciante_id uuid          NOT NULL,
    data           date          NOT NULL,   -- data de negócio (mesma do lançamento)
    valor_signed   numeric(18,2) NOT NULL,   -- crédito > 0 · débito < 0
    processado_em  timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_processed_event PRIMARY KEY (event_id, data)  -- chave-partição inclusa
) PARTITION BY RANGE (data);

-- Recomputo do invariante + rollup por {comerciante,data}.
CREATE INDEX ix_processed_event_saldo
    ON consolidado.processed_event (comerciante_id, data);

-- Watermark do rollup (janela append-only).
CREATE INDEX ix_processed_event_processado_em
    ON consolidado.processed_event (processado_em);

-- -----------------------------------------------------------------------------
-- saldo_diario: PROJEÇÃO por {comerciante,data}. Alvo do rollup ON CONFLICT.
-- PK (comerciante_id, data) = UNIQUE que o ON CONFLICT DO UPDATE exige.
-- Escrito SÓ pelo rollup (1 UPDATE por grupo/janela), NUNCA por evento.
-- -----------------------------------------------------------------------------
CREATE TABLE consolidado.saldo_diario (
    comerciante_id uuid          NOT NULL,
    data           date          NOT NULL,
    valor          numeric(18,2) NOT NULL DEFAULT 0,
    atualizado_em  timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_saldo_diario PRIMARY KEY (comerciante_id, data)
);

-- -----------------------------------------------------------------------------
-- rollup_controle: watermark do job de rollup (1 linha singleton). Estado do
-- processo (não dado de negócio): permite rollup idempotente por janela (ADR-006).
-- -----------------------------------------------------------------------------
CREATE TABLE consolidado.rollup_controle (
    id                   boolean     PRIMARY KEY DEFAULT true CHECK (id),
    ultimo_processado_em timestamptz NOT NULL DEFAULT 'epoch'
);

-- -----------------------------------------------------------------------------
-- descarte_logado: descarte EXPLÍCITO e contável (nunca silencioso).
-- Termo do invariante: N(aceitos)=consumidos+fila+DLQ+descartes (harness/invariante.sql).
-- -----------------------------------------------------------------------------
CREATE TABLE consolidado.descarte_logado (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id       uuid,
    motivo         text  NOT NULL,   -- ex.: 'POISON','SEM_DESTINO','REPLAY_OK'
    detalhe        jsonb,
    correlation_id uuid,
    criado_em      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_descarte_logado_criado_em
    ON consolidado.descarte_logado (criado_em);

-- -----------------------------------------------------------------------------
-- PARTIÇÕES. Exemplo mensal (2026-10) + DEFAULT p/ qualquer mês não pré-criado.
-- Job de manutenção pré-cria o mês seguinte e destaca os antigos (ADR-010).
-- -----------------------------------------------------------------------------
CREATE TABLE consolidado.processed_event_2026_10
    PARTITION OF consolidado.processed_event
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');

CREATE TABLE consolidado.processed_event_default
    PARTITION OF consolidado.processed_event DEFAULT;

-- =============================================================================
-- PII (ADR-010): não há campo sensível declarado nos insumos. Descartar/payloads
-- NÃO carregam PII além de identificadores; logs usam correlation_id/event_id.
-- =============================================================================

-- =============================================================================
-- OBSERVAÇÃO: o INSERT append-only + rollup (ADR-004/006) NÃO precisa de trigger.
-- Exemplo do consumidor (ack no broker SÓ após COMMIT):
--   INSERT INTO consolidado.processed_event (event_id, comerciante_id, data, valor_signed)
--   VALUES ($1,$2,$3,$4)
--   ON CONFLICT (event_id, data) DO NOTHING;   -- 1×; reentrega = no-op
--   COMMIT;                                    -- então ack
-- Exemplo do rollup (job interno, mesma tx):
--   INSERT INTO consolidado.saldo_diario AS s (comerciante_id, data, valor, atualizado_em)
--   SELECT comerciante_id, data, sum(valor_signed), now()
--     FROM consolidado.processed_event
--    WHERE processado_em > (SELECT ultimo_processado_em FROM consolidado.rollup_controle)
--    GROUP BY comerciante_id, data
--   ON CONFLICT (comerciante_id, data) DO UPDATE
--     SET valor = s.valor + EXCLUDED.valor, atualizado_em = now();
-- =============================================================================

-- =============================================================================
-- ROLLBACK / DOWN (manual; Flyway community não roda down automático).
--   DROP TABLE IF EXISTS consolidado.descarte_logado;
--   DROP TABLE IF EXISTS consolidado.rollup_controle;
--   DROP TABLE IF EXISTS consolidado.saldo_diario;
--   DROP TABLE IF EXISTS consolidado.processed_event;   -- inclui partições
--   DROP SCHEMA IF EXISTS consolidado;
-- =============================================================================
