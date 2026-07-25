-- V007: route_risk_observation as TimescaleDB hypertable
-- Requires TimescaleDB extension

CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE TABLE route_risk_observation (
    time            TIMESTAMPTZ NOT NULL,
    saved_route_id  UUID NOT NULL,
    risk_score      INT CHECK (risk_score BETWEEN 0 AND 100),
    risk_level      TEXT CHECK (risk_level IN ('LOW', 'MODERATE', 'HIGH', 'SEVERE')),
    factors         JSONB NOT NULL DEFAULT '{}'::jsonb
);

-- Convert to hypertable with 7-day chunks
SELECT create_hypertable(
    'route_risk_observation',
    'time',
    chunk_time_interval => INTERVAL '7 days',
    if_not_exists => TRUE
);

-- Index for per-route time-series queries
CREATE INDEX idx_risk_obs_route_time
    ON route_risk_observation (saved_route_id, time DESC);

-- Continuous aggregate: hourly risk summary per route
CREATE MATERIALIZED VIEW cagg_risk_hourly
WITH (timescaledb.continuous) AS
SELECT
    time_bucket('1 hour', time) AS bucket,
    saved_route_id,
    avg(risk_score)::numeric(5,2) AS avg_risk_score,
    max(risk_score) AS max_risk_score,
    min(risk_score) AS min_risk_score,
    count(*) AS observation_count
FROM route_risk_observation
GROUP BY bucket, saved_route_id
WITH NO DATA;

-- Refresh policy: keep hourly aggregate up to date
SELECT add_continuous_aggregate_policy('cagg_risk_hourly',
    start_offset    => INTERVAL '3 hours',
    end_offset      => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 hour',
    if_not_exists   => TRUE
);

-- Continuous aggregate: daily risk summary per route
CREATE MATERIALIZED VIEW cagg_risk_daily
WITH (timescaledb.continuous) AS
SELECT
    time_bucket('1 day', time) AS bucket,
    saved_route_id,
    avg(risk_score)::numeric(5,2) AS avg_risk_score,
    max(risk_score) AS max_risk_score,
    min(risk_score) AS min_risk_score,
    count(*) AS observation_count
FROM route_risk_observation
GROUP BY bucket, saved_route_id
WITH NO DATA;

SELECT add_continuous_aggregate_policy('cagg_risk_daily',
    start_offset    => INTERVAL '3 days',
    end_offset      => INTERVAL '1 day',
    schedule_interval => INTERVAL '1 day',
    if_not_exists   => TRUE
);

-- Retention policy: keep raw observations for 90 days
SELECT add_retention_policy('route_risk_observation',
    drop_after => INTERVAL '90 days',
    if_not_exists => TRUE
);
