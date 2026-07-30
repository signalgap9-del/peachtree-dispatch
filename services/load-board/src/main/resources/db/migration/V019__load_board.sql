CREATE TABLE IF NOT EXISTS freight_load (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         UUID NOT NULL,
    origin            VARCHAR(100) NOT NULL,
    destination       VARCHAR(100) NOT NULL,
    cargo_type        VARCHAR(20) NOT NULL DEFAULT 'GENERAL',
    weight_kg         INTEGER,
    pickup_start      TIMESTAMPTZ,
    pickup_end        TIMESTAMPTZ,
    delivery_deadline TIMESTAMPTZ,
    max_rate_cents    INTEGER,
    corridor_id       VARCHAR(20),
    corridor_risk     SMALLINT,
    status            VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    version           INTEGER NOT NULL DEFAULT 1,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_load_status_corridor
    ON freight_load (status, corridor_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_load_tenant
    ON freight_load (tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_load_corridor_risk
    ON freight_load (corridor_id, corridor_risk DESC)
    WHERE status = 'OPEN';

CREATE MATERIALIZED VIEW IF NOT EXISTS mv_open_loads_summary AS
SELECT corridor_id, cargo_type,
       count(*) AS open_count,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY max_rate_cents) AS median_rate,
       avg(corridor_risk)::smallint AS avg_risk,
       min(created_at) AS oldest_load
FROM freight_load
WHERE status = 'OPEN'
GROUP BY corridor_id, cargo_type;

CREATE UNIQUE INDEX IF NOT EXISTS idx_mv_open_loads
    ON mv_open_loads_summary (corridor_id, cargo_type);
