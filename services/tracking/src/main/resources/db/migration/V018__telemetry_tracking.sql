-- FreightScaler Phase 2: Fleet Telemetry Tracking
-- TimescaleDB hypertable for GPS tracking events
-- Scale: 28.8M events/day (10k trucks × 2880 pings/day)

CREATE TABLE IF NOT EXISTS tracking_event (
    time          TIMESTAMPTZ     NOT NULL,
    truck_id      UUID            NOT NULL,
    corridor_id   VARCHAR(20),
    lat           DOUBLE PRECISION NOT NULL,
    lon           DOUBLE PRECISION NOT NULL,
    speed_kmh     REAL,
    heading       SMALLINT,
    risk_score    SMALLINT
);

-- Convert to hypertable with 7-day chunks
SELECT create_hypertable('tracking_event', 'time',
    chunk_time_interval => INTERVAL '7 days',
    if_not_exists => TRUE
);

-- Indexes for access patterns
CREATE INDEX IF NOT EXISTS idx_tracking_truck_time
    ON tracking_event (truck_id, time DESC);
CREATE INDEX IF NOT EXISTS idx_tracking_corridor_time
    ON tracking_event (corridor_id, time DESC)
    WHERE corridor_id IS NOT NULL;

-- Covering index for history queries (Index Only Scan)
CREATE INDEX IF NOT EXISTS idx_tracking_truck_time_covering
    ON tracking_event (truck_id, time DESC)
    INCLUDE (lat, lon, speed_kmh, heading);

-- TimescaleDB compression: segment by truck, order by time
ALTER TABLE tracking_event SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'truck_id',
    timescaledb.compress_orderby = 'time DESC'
);

-- Compress chunks older than 7 days (hot: 0-7d uncompressed, warm: 7-30d compressed)
SELECT add_compression_policy('tracking_event', INTERVAL '7 days', if_not_exists => TRUE);

-- Drop chunks older than 30 days (retention)
SELECT add_retention_policy('tracking_event', INTERVAL '30 days', if_not_exists => TRUE);

-- Continuous aggregate: hourly position summary per truck
-- WITH NO DATA so creation can run inside Flyway's transaction; the refresh
-- policy below populates it incrementally.
CREATE MATERIALIZED VIEW IF NOT EXISTS cagg_tracking_hourly
WITH (timescaledb.continuous) AS
SELECT
    time_bucket('1 hour', time) AS bucket,
    truck_id,
    corridor_id,
    avg(speed_kmh) AS avg_speed,
    max(speed_kmh) AS max_speed,
    count(*) AS ping_count,
    avg(lat) AS avg_lat,
    avg(lon) AS avg_lon
FROM tracking_event
GROUP BY bucket, truck_id, corridor_id
WITH NO DATA;

-- Refresh policy: refresh hourly aggregate every 10 minutes
SELECT add_continuous_aggregate_policy('cagg_tracking_hourly',
    start_offset => INTERVAL '3 hours',
    end_offset => INTERVAL '10 minutes',
    schedule_interval => INTERVAL '10 minutes',
    if_not_exists => TRUE
);
