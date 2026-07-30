CREATE TABLE IF NOT EXISTS settlement (
    id                  BIGSERIAL PRIMARY KEY,
    load_id             BIGINT NOT NULL,
    bid_id              BIGINT NOT NULL,
    carrier_id          UUID NOT NULL,
    shipper_id          UUID NOT NULL,
    base_rate_cents     INTEGER NOT NULL,
    adjustment_cents    INTEGER NOT NULL DEFAULT 0,
    final_amount_cents  INTEGER,
    status              VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    current_step        INTEGER NOT NULL DEFAULT 0,
    saga_log            JSONB NOT NULL DEFAULT '[]',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_settlement_carrier ON settlement (carrier_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_settlement_shipper ON settlement (shipper_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_settlement_load ON settlement (load_id);

CREATE TABLE IF NOT EXISTS wallet (
    owner_id        UUID PRIMARY KEY,
    balance_cents   BIGINT NOT NULL DEFAULT 0,
    version         INTEGER NOT NULL DEFAULT 1,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS settlement_step_log (
    id              BIGSERIAL PRIMARY KEY,
    settlement_id   BIGINT NOT NULL REFERENCES settlement(id),
    step_number     INTEGER NOT NULL,
    step_name       VARCHAR(50) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    detail          JSONB,
    executed_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_step_log ON settlement_step_log (settlement_id, step_number);
