CREATE TABLE IF NOT EXISTS bid (
    id              BIGSERIAL PRIMARY KEY,
    load_id         BIGINT NOT NULL,
    carrier_id      UUID NOT NULL,
    rate_cents      INTEGER NOT NULL,
    estimated_hours REAL,
    risk_ack        BOOLEAN NOT NULL DEFAULT false,
    status          VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_bid_load ON bid (load_id, created_at);
CREATE INDEX IF NOT EXISTS idx_bid_carrier ON bid (carrier_id, status, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS idx_bid_unique_active ON bid (load_id, carrier_id) WHERE status != 'WITHDRAWN';
