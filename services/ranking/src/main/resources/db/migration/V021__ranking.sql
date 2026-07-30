CREATE TABLE IF NOT EXISTS carrier_profile (
    carrier_id    UUID PRIMARY KEY,
    name          VARCHAR(200) NOT NULL,
    cargo_certs   VARCHAR(100)[],
    home_region   VARCHAR(50),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ranking_snapshot (
    id            BIGSERIAL PRIMARY KEY,
    snapshot_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    category      VARCHAR(50) NOT NULL,
    corridor_id   VARCHAR(20),
    carrier_id    UUID NOT NULL,
    score         DOUBLE PRECISION NOT NULL,
    rank          INTEGER
);

CREATE INDEX IF NOT EXISTS idx_ranking_snap_lookup
    ON ranking_snapshot (category, corridor_id, snapshot_time DESC);
CREATE INDEX IF NOT EXISTS idx_ranking_snap_carrier
    ON ranking_snapshot (carrier_id, snapshot_time DESC);
