-- =============================================================================
-- Verety: Flyway V1, esquema `lancamentos` (WRITE-SIDE)
-- Fonte canônica do DDL: docs/schema.sql. Adaptada para migração
-- versionada e para o build de app (Spring Boot + Flyway).
-- Doutrina: ADR-003 (Transactional Outbox) · ADR-005 (Idempotency-Key) ·
--           ADR-010 (retenção/partição). Dinheiro SEMPRE numeric(18,2).
-- Fronteira: ZERO FK cross-schema (CQRS lógico, acoplamento por evento).
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS lancamentos;

-- -----------------------------------------------------------------------------
-- lancamento: registro de débito/crédito. Dedup de ORIGEM (retry do cliente).
-- PARTITION BY RANGE (data): retenção/archival (ADR-010). O `data` (data de
-- negócio, TZ de ADR-012, nunca now()) COMPÕE a PK/UNIQUE porque o Postgres
-- exige a chave de particionamento em toda constraint única.
-- -----------------------------------------------------------------------------
CREATE TABLE lancamentos.lancamento (
    id             bigint GENERATED ALWAYS AS IDENTITY,
    transaction_id uuid          NOT NULL,   -- Idempotency-Key da origem (ADR-005)
    comerciante_id uuid          NOT NULL,
    data           date          NOT NULL,   -- DATA DE NEGÓCIO (não now())
    tipo           text          NOT NULL CHECK (tipo IN ('CREDITO','DEBITO')),
    valor          numeric(18,2) NOT NULL CHECK (valor > 0),
    criado_em      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_lancamento              PRIMARY KEY (comerciante_id, data, id),
    CONSTRAINT uq_lancamento_transaction  UNIQUE (comerciante_id, data, transaction_id)
) PARTITION BY RANGE (data);

-- Atende RF04 (saldo do dia) e serve de apoio ao UNIQUE acima: sem seq scan.
CREATE INDEX ix_lancamento_comerciante_data
    ON lancamentos.lancamento (comerciante_id, data);

-- -----------------------------------------------------------------------------
-- outbox: Transactional Outbox (ADR-003). 201 no COMMIT; broker fora da request.
-- NÃO particionada (PK simples `id`); o relay faz SKIP LOCKED + índice PARCIAL.
-- -----------------------------------------------------------------------------
CREATE TABLE lancamentos.outbox (
    id           bigint GENERATED ALWAYS AS IDENTITY,
    aggregate_id bigint      NOT NULL,   -- = lancamento.id (referência LÓGICA, sem FK)
    event_id     uuid        NOT NULL,   -- = transaction_id (estável) → dedup do consumidor
    event_type   text        NOT NULL,   -- ex.: 'LancamentoCriado'
    payload      jsonb       NOT NULL,
    status       text        NOT NULL DEFAULT 'PENDING'
                 CHECK (status IN ('PENDING','PUBLISHED','FAILED')),
    attempts     integer     NOT NULL DEFAULT 0,
    next_attempt timestamptz NOT NULL DEFAULT now(),
    locked_until timestamptz,            -- reserva do relay (FOR UPDATE SKIP LOCKED)
    created_at   timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    CONSTRAINT pk_outbox          PRIMARY KEY (id),
    CONSTRAINT uq_outbox_event_id UNIQUE (event_id)  -- 1 evento por linha
);

-- Relay: só varre PENDING vencido. Índice PARCIAL (não incha com PUBLISHED/FAILED).
CREATE INDEX ix_outbox_pending
    ON lancamentos.outbox (next_attempt, id)
    WHERE status = 'PENDING';

-- Triagem do estado TERMINAL (poison-outbox / alarme: ADR-009).
CREATE INDEX ix_outbox_failed
    ON lancamentos.outbox (created_at)
    WHERE status = 'FAILED';

-- -----------------------------------------------------------------------------
-- PARTIÇÕES. Exemplo mensal (2026-10, data de referência da sessão) + DEFAULT
-- que absorve qualquer mês não pré-criado. Em Alvo, um job de manutenção
-- pré-cria o mês seguinte e destaca os antigos p/ cold store (ADR-010).
-- -----------------------------------------------------------------------------
CREATE TABLE lancamentos.lancamento_2026_10
    PARTITION OF lancamentos.lancamento
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');

CREATE TABLE lancamentos.lancamento_default
    PARTITION OF lancamentos.lancamento DEFAULT;

-- =============================================================================
-- ROLLBACK / DOWN (Flyway community não roda down automático: manual).
-- Nenhuma migração destrutiva sem backup/PITR (ADR-010).
--   DROP TABLE IF EXISTS lancamentos.outbox;      -- inclui índices
--   DROP TABLE IF EXISTS lancamentos.lancamento;  -- inclui partições/índices
--   DROP SCHEMA IF EXISTS lancamentos;
-- =============================================================================
