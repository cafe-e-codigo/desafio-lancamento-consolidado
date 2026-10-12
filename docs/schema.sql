-- =============================================================================
-- Verety: DDL canônico (PostgreSQL)
-- Autoria: José Airton
-- Fonte de verdade: Postgres. Doutrina: ADR-004 (idempotência 1 SQL / append-only),
--   ADR-006 (read-side: Redis read-through + single-flight), ADR-010 (retenção/pool/PII).
-- Régua: PDF prevalece. Dinheiro SEMPRE numeric(18,2), nunca float/INCRBYFLOAT (RF02 §2.1).
-- Escopo: mono-comerciante · 2 esquemas (lancamentos × consolidado) · ZERO FK cross-schema
--   (CQRS lógico, acoplamento por evento).
-- Alvo semântico: idêntico em PostgreSQL local (compose) e Aurora PostgreSQL (semântica, não capacidade).
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 0. Esquemas (fronteira lógica CQRS: um banco físico, dois contextos)
-- -----------------------------------------------------------------------------
CREATE SCHEMA IF NOT EXISTS lancamentos;   -- write-side (Serviço de Lançamentos)
CREATE SCHEMA IF NOT EXISTS consolidado;   -- read-side/projeção (Serviço de Consolidado)

-- Extensão para gen_random_uuid(); em Aurora/PG>=13 já existe em pgcrypto.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- =============================================================================
-- WRITE-SIDE · esquema lancamentos
-- =============================================================================

-- -----------------------------------------------------------------------------
-- lancamento: registro de débito/crédito. Dedup de ORIGEM (retry de cliente).
-- -----------------------------------------------------------------------------
CREATE TABLE lancamentos.lancamento (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    transaction_id  uuid        NOT NULL,          -- Idempotency-Key da origem (ADR-005)
    comerciante_id  uuid        NOT NULL,
    data            date        NOT NULL,          -- DATA DE NEGÓCIO (TZ de ADR-012), não now()
    tipo            text        NOT NULL CHECK (tipo IN ('CREDITO','DEBITO')),
    valor           numeric(18,2) NOT NULL CHECK (valor > 0),
    criado_em       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_lancamento PRIMARY KEY (comerciante_id, data, id),
    CONSTRAINT uq_lancamento_transaction UNIQUE (comerciante_id, data, transaction_id)
) PARTITION BY RANGE (data);                       -- retenção/archival (ADR-010)

-- atendem RF04 (saldo do dia) e o UNIQUE acima: sem seq scan
CREATE INDEX ix_lancamento_comerciante_data ON lancamentos.lancamento (comerciante_id, data);

-- -----------------------------------------------------------------------------
-- outbox: Transactional Outbox (ADR-003). 201 no COMMIT; broker fora do request.
-- -----------------------------------------------------------------------------
CREATE TABLE lancamentos.outbox (
    id             bigint GENERATED ALWAYS AS IDENTITY,
    aggregate_id   bigint      NOT NULL,            -- = lancamento.id (referência lógica)
    event_id       uuid        NOT NULL,            -- = transaction_id (estável) → dedup do consumidor
    event_type     text        NOT NULL,            -- ex.: 'LancamentoCriado'
    payload        jsonb       NOT NULL,
    status         text        NOT NULL DEFAULT 'PENDING'
                   CHECK (status IN ('PENDING','PUBLISHED','FAILED')),
    attempts       integer     NOT NULL DEFAULT 0,
    next_attempt   timestamptz NOT NULL DEFAULT now(),
    locked_until   timestamptz,                      -- reserva do relay (SKIP LOCKED)
    created_at     timestamptz NOT NULL DEFAULT now(),
    published_at   timestamptz,
    CONSTRAINT pk_outbox PRIMARY KEY (id),
    CONSTRAINT uq_outbox_event UNIQUE (event_id)     -- 1 evento por linha (dedup do relay)
);

-- relay: só varre PENDING vencido; índice PARCIAL (não incha com PUBLISHED/FAILED)
CREATE INDEX ix_outbox_pending
    ON lancamentos.outbox (next_attempt, id)
    WHERE status = 'PENDING';

-- triagem do estado TERMINAL (poison-outbox / alarme, ADR-009)
CREATE INDEX ix_outbox_failed
    ON lancamentos.outbox (created_at)
    WHERE status = 'FAILED';

-- =============================================================================
-- READ-SIDE · esquema consolidado
-- =============================================================================

-- -----------------------------------------------------------------------------
-- processed_event: LEDGER DURÁVEL = dedup idempotente (substitui SETNX+TIF 48h).
-- 1 linha por evento, PARA SEMPRE (retenção ≥ janela de reprocesso, ADR-010).
-- Funde o "marker de dedup" + o "incremento append-only" do ADR-004: o próprio
-- marcador carrega valor_signed. NÃO há TTL (TTL reintroduziria dupla contagem).
-- -----------------------------------------------------------------------------
CREATE TABLE consolidado.processed_event (
    event_id       uuid        NOT NULL,
    comerciante_id uuid        NOT NULL,
    data           date        NOT NULL,            -- data de negócio (mesma do lançamento)
    valor_signed   numeric(18,2) NOT NULL,          -- crédito > 0 · débito < 0
    processado_em  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_processed_event PRIMARY KEY (event_id, data)   -- chave-partição inclusa
) PARTITION BY RANGE (data);                       -- retenção/archival (ADR-010)

-- recomputo do invariante + rollup por {comerciante,data}
CREATE INDEX ix_processed_event_saldo ON consolidado.processed_event (comerciante_id, data);
-- watermark do rollup (janela append-only)
CREATE INDEX ix_processed_event_processado_em ON consolidado.processed_event (processado_em);

-- -----------------------------------------------------------------------------
-- saldo_diario: PROJEÇÃO por {comerciante,data}. Alvo do rollup ON CONFLICT.
-- PK (comerciante_id, data) = UNIQUE que o ON CONFLICT DO UPDATE exige.
-- -----------------------------------------------------------------------------
CREATE TABLE consolidado.saldo_diario (
    comerciante_id uuid        NOT NULL,
    data           date        NOT NULL,
    valor          numeric(18,2) NOT NULL DEFAULT 0,
    atualizado_em  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_saldo_diario PRIMARY KEY (comerciante_id, data)
);

-- -----------------------------------------------------------------------------
-- rollup_controle: watermark do job de rollup (1 linha singleton). Estado do
-- processo, não dado de negócio: permite rollup idempotente por janela (ADR-006).
-- -----------------------------------------------------------------------------
CREATE TABLE consolidado.rollup_controle (
    id                  boolean     PRIMARY KEY DEFAULT true CHECK (id),
    ultimo_processado_em timestamptz NOT NULL DEFAULT 'epoch'
);

-- -----------------------------------------------------------------------------
-- descarte_logado: descarte EXPLÍCITO e contável (nunca silencioso).
-- Termo do invariante de reconciliação: N(aceitos)=consumidos+fila+DLQ+descartes.
-- -----------------------------------------------------------------------------
CREATE TABLE consolidado.descarte_logado (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id       uuid,
    motivo         text        NOT NULL,            -- ex.: 'POISON','SEM_DESTINO','REPLAY_OK'
    detalhe        jsonb,
    correlation_id uuid,
    criado_em      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_descarte_logado_criado_em ON consolidado.descarte_logado (criado_em);

-- =============================================================================
-- PII (ADR-010): não há campo sensível declarado nos insumos. Política preventiva:
-- descartes/payloads NÃO carregam PII além de identificadores; logs usam
-- correlation_id/event_id (redação). Se eu declarar PII → cifra KMS + reavaliar.
-- =============================================================================

-- =============================================================================
-- OBSERVAÇÃO: o INSERT append-only + rollup (ADR-004/ADR-006) NÃO precisa de
-- trigger: a aplicação faz 1 INSERT ON CONFLICT DO NOTHING e o job de rollup
-- agrega a janela. Nenhum UPDATE por evento toca saldo_diario (mata o hot-row).
-- Exemplo do consumidor (ack no broker SÓ após COMMIT):
--
--   INSERT INTO consolidado.processed_event (event_id, comerciante_id, data, valor_signed)
--   VALUES ($1, $2, $3, $4)
--   ON CONFLICT (event_id, data) DO NOTHING;          -- 1×; reentrega = no-op
--   COMMIT;                                            -- então ack
--
-- Exemplo do rollup (job interno, mesma tx):
--
--   INSERT INTO consolidado.saldo_diario AS s (comerciante_id, data, valor, atualizado_em)
--   SELECT comerciante_id, data, sum(valor_signed), now()
--     FROM consolidado.processed_event
--    WHERE processado_em > (SELECT ultimo_processado_em FROM consolidado.rollup_controle)
--    GROUP BY comerciante_id, data
--   ON CONFLICT (comerciante_id, data) DO UPDATE
--     SET valor = s.valor + EXCLUDED.valor, atualizado_em = now();
-- =============================================================================

-- =============================================================================
-- PARTIÇÕES: exemplo mensal (o job de manutenção cria a partição do mês seguinte
-- e destaca as antigas para o cold store; ADR-010). Não criar partições dinâmicas
-- em produção sem o job, e aqui ficam 2 partições de exemplo para o compose local.
-- =============================================================================
CREATE TABLE lancamentos.lancamento_2026_10
    PARTITION OF lancamentos.lancamento
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');

CREATE TABLE consolidado.processed_event_2026_10
    PARTITION OF consolidado.processed_event
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');

CREATE TABLE lancamentos.lancamento_default
    PARTITION OF lancamentos.lancamento DEFAULT;

CREATE TABLE consolidado.processed_event_default
    PARTITION OF consolidado.processed_event DEFAULT;

-- =============================================================================
-- ÍNDICES DE RECONCILIAÇÃO (indice de reconciliacao, invariante de contagem). Rodar FORA da app:
--   N(consumido) = COUNT(DISTINCT event_id) FROM consolidado.processed_event
--   saldo_diario.valor == SUM(processed_event.valor_signed) por {comerciante,data}
-- Proibido: SCAN idempotency:* no Redis (mente sob TTL/eviction) e contador interno.
-- =============================================================================

-- =============================================================================
-- ROLLBACK / DOWN (ADR: rollback explícito). Ordem inversa da criação; DROP dos
-- esquemas remove tudo de uma vez. Nenhuma migração destrutiva sem backup/PITR.
--   DROP TABLE IF EXISTS consolidado.descarte_logado;
--   DROP TABLE IF EXISTS consolidado.rollup_controle;
--   DROP TABLE IF EXISTS consolidado.saldo_diario;
--   DROP TABLE IF EXISTS consolidado.processed_event;   -- inclui partições
--   DROP TABLE IF EXISTS lancamentos.outbox;
--   DROP TABLE IF EXISTS lancamentos.lancamento;        -- inclui partições
--   DROP SCHEMA IF EXISTS consolidado;
--   DROP SCHEMA IF EXISTS lancamentos;
-- =============================================================================
