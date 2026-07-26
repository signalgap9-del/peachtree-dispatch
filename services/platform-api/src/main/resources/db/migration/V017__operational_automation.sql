-- V017: Operational automation - query monitoring, tiering, scheduled maintenance
--
-- Implements the operational program from ADR-0020 (database scale & sharding):
--   1. pg_stat_statements for query performance monitoring
--   2. TimescaleDB compression policy on route_risk_observation (hot/warm tiering)
--   3. Initial population + scheduled CONCURRENTLY refresh of the V012 MVIEWs
--   4. Proactive next-month partition creation for usage_record (V004)
--   5. TTL sweeper for idempotency_key (expires_at from V011)
--   6. No-op audit_log archive stub (activates with the S3 export pipeline)
--
-- ============================================================================
-- SERVER CONFIG PREREQUISITE (cannot be set from SQL)
-- ============================================================================
-- pg_cron is NOT bundled in timescale/timescaledb:latest-pg16. The
-- production-data profile in compose.yaml therefore uses
-- timescale/timescaledb-ha:pg16, which ships pg_cron (on RDS/Aurora pg_cron
-- is available natively). On images without pg_cron this migration still
-- applies cleanly: the scheduler section is skipped with a WARNING and the
-- rest (monitoring, compression, MVIEW population, helper functions) works.
--
-- Where pg_cron exists, the server must also preload it:
--
--   shared_preload_libraries = 'timescaledb,pg_stat_statements,pg_cron'
--   cron.database_name = 'atmospath'
--
-- 'timescaledb' MUST stay first: the timescale/timescaledb image sets it in
-- postgresql.conf, and a command-line -c flag OVERRIDES that file, so the
-- full list must be repeated. compose.yaml sets these on postgres-primary.
-- On RDS/Aurora they go in the DB parameter group (cron.database_name =>
-- the app database name).
--
-- If pg_cron is installed but not preloaded, jobs are created but never
-- fire; the scheduling block in section 7 raises a WARNING when it detects
-- a missing preload.
--
-- All cron schedules are UTC (server timezone).

-- ============================================================================
-- 1. Query monitoring: pg_stat_statements
-- ============================================================================
-- Tracks per-query planning/execution statistics. Requires preloading (see
-- header) or statement collection is inactive.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- Convenience view over the hottest queries. Reading it requires superuser
-- or pg_read_all_stats. Use it to find missing indexes (high rows_per_call),
-- latency offenders (high mean_ms), and total-cost leaders (high total_ms).
-- Reset the baseline periodically with SELECT pg_stat_statements_reset();
CREATE OR REPLACE VIEW v_query_performance AS
SELECT
    queryid,
    calls,
    round(mean_exec_time::numeric, 2)          AS mean_ms,
    round(total_exec_time::numeric, 2)         AS total_ms,
    rows,
    round(rows::numeric / NULLIF(calls, 0), 1) AS rows_per_call,
    left(query, 200)                           AS query_preview
FROM pg_stat_statements
WHERE query !~* 'pg_stat_statements|v_query_performance'
ORDER BY total_exec_time DESC;

-- ============================================================================
-- 2. Scheduler availability gate: pg_cron
-- ============================================================================
-- Creates the extension only when the image provides it. The preload sanity
-- warning happens in section 7, next to the job definitions.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'pg_cron') THEN
        EXECUTE 'CREATE EXTENSION IF NOT EXISTS pg_cron';
    ELSE
        RAISE WARNING
            'pg_cron is not available in this PostgreSQL image. V017 scheduled jobs were NOT created. Use timescale/timescaledb-ha (compose production-data profile) or install pg_cron, then re-run the section 7 block.';
    END IF;

    IF current_setting('shared_preload_libraries') NOT LIKE '%pg_stat_statements%' THEN
        RAISE WARNING
            'pg_stat_statements is not in shared_preload_libraries (%). The extension is created but statement tracking is inactive until the server is reconfigured.',
            current_setting('shared_preload_libraries');
    END IF;
END $$;

-- ============================================================================
-- 3. Hot/warm tiering: compress observation chunks older than 30 days
-- ============================================================================
-- Closes the gap noted in ADR-0020: production-scaling.md described this
-- policy, but V007 only created the 90-day retention (drop) policy.
-- segmentby saved_route_id keeps per-route time-range scans efficient inside
-- compressed chunks; orderby time DESC matches the dominant query pattern.
--
-- Caveat: compressed chunks cannot serve the HNSW embedding index (V013)
-- without decompression. Acceptable because RAG grounding targets recent
-- observations (ADR-0016/0018). If ANN search over warm history becomes a
-- requirement, exclude embedding-bearing chunks or move vectors out.
ALTER TABLE route_risk_observation SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'saved_route_id',
    timescaledb.compress_orderby   = 'time DESC'
);

SELECT add_compression_policy('route_risk_observation',
    compress_after => INTERVAL '30 days',
    if_not_exists  => TRUE
);

-- NOTE: raw-observation RETENTION (drop chunks > 90 days) is already enforced
-- by the add_retention_policy call in V007. The hourly/daily continuous
-- aggregates (cagg_risk_hourly, cagg_risk_daily) self-refresh via the
-- add_continuous_aggregate_policy calls in V007. Neither needs pg_cron;
-- duplicating them here would double the work.

-- ============================================================================
-- 4. Materialized view refresh (V012: mv_workspace_usage_summary,
--    mv_tenant_risk_summary)
-- ============================================================================
-- Both views were created WITH NO DATA, and REFRESH ... CONCURRENTLY requires
-- at least one full (non-concurrent) refresh first. Do that now.
--
-- RLS NOTE: refresh runs the view query as the scheduling role. Under
-- FORCE ROW LEVEL SECURITY (V009) a role without bypass would see zero rows
-- (app.tenant_id is unset) and silently refresh the views EMPTY. The
-- migration role is superuser in Docker dev and rds_superuser on RDS, both
-- of which bypass RLS. If Flyway ever runs as a least-privilege role, that
-- role must be granted BYPASSRLS before this migration.
REFRESH MATERIALIZED VIEW mv_workspace_usage_summary;
REFRESH MATERIALIZED VIEW mv_tenant_risk_summary;

-- ============================================================================
-- 5. Proactive partition creation for usage_record
-- ============================================================================
-- V004's BEFORE INSERT trigger creates partitions on demand, but only when a
-- row arrives - the first insert of a new month pays the DDL latency, and a
-- quiet system could cross month boundaries with no partition ready. This
-- function creates the CURRENT and NEXT month's partitions ahead of time,
-- mirroring the V004 naming convention (usage_record_YYYY_MM).
CREATE OR REPLACE FUNCTION create_next_usage_partition()
RETURNS TEXT[]
LANGUAGE plpgsql
AS $$
DECLARE
    m_offset       INT;
    month_start    DATE;
    partition_name TEXT;
    created        TEXT[] := '{}';
BEGIN
    FOR m_offset IN 0..1 LOOP
        month_start    := (date_trunc('month', CURRENT_DATE)
                           + make_interval(months => m_offset))::date;
        partition_name := 'usage_record_' || to_char(month_start, 'YYYY_MM');

        IF NOT EXISTS (SELECT 1 FROM pg_class WHERE relname = partition_name) THEN
            EXECUTE format(
                'CREATE TABLE %I PARTITION OF usage_record FOR VALUES FROM (%L) TO (%L)',
                partition_name,
                month_start,
                (month_start + INTERVAL '1 month')::date
            );
            created := array_append(created, partition_name);
        END IF;
    END LOOP;

    RETURN created;
END;
$$;

-- ============================================================================
-- 6. Audit log archive stub (NO-OP by default - see ADR-0020)
-- ============================================================================
-- audit_log is an unpartitioned plain table with no retention today. This
-- stub defines the operational entry point so the retention job has a stable
-- interface, but it archives NOTHING until the S3 export pipeline exists.
-- The monthly cron schedule below is intentionally left commented out.
CREATE OR REPLACE FUNCTION archive_audit_log(p_retain_days INT DEFAULT 730)
RETURNS BIGINT
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE NOTICE
        'archive_audit_log stub invoked (retain_days=%). No rows archived: S3 export pipeline not implemented yet (ADR-0020).', p_retain_days;
    RETURN 0;

    -- ACTIVATION PLAYBOOK (when the cold-export pipeline lands):
    -- 1. Convert audit_log to monthly RANGE partitions (like usage_record),
    --    so archival is DETACH + upload instead of row deletes.
    -- 2. Replace this body with:
    --      COPY (SELECT * FROM audit_log
    --            WHERE created_at < now() - make_interval(days => p_retain_days))
    --        TO PROGRAM 'aws s3 cp - s3://<bucket>/audit/archive.csv.gz';
    --      DELETE FROM audit_log
    --       WHERE created_at < now() - make_interval(days => p_retain_days);
    -- 3. Uncomment the cron schedule below.
    -- Retention windows: docs/data-retention.md (2 years online, 7 years cold).
END;
$$;

-- ============================================================================
-- 7. Job schedules
-- ============================================================================
-- Jobs run in cron.database_name (atmospath) as the role that scheduled them
-- (the migration role). Guards make re-running this block safe. The whole
-- block no-ops when pg_cron is unavailable (warning emitted in section 2).
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_cron') THEN
        RETURN;
    END IF;

    IF current_setting('shared_preload_libraries') NOT LIKE '%pg_cron%' THEN
        RAISE WARNING
            'pg_cron is installed but not in shared_preload_libraries (%). The jobs below exist but will NEVER RUN until the server is reconfigured.',
            current_setting('shared_preload_libraries');
    END IF;

    -- Usage dashboard MVIEW: every 5 minutes. CONCURRENTLY avoids blocking
    -- readers; safe because uq_mv_workspace_usage exists (V012).
    IF NOT EXISTS (SELECT 1 FROM cron.job WHERE jobname = 'refresh_mv_workspace_usage_summary') THEN
        PERFORM cron.schedule(
            'refresh_mv_workspace_usage_summary',
            '*/5 * * * *',
            'REFRESH MATERIALIZED VIEW CONCURRENTLY mv_workspace_usage_summary'
        );
    END IF;

    -- Risk dashboard MVIEW: hourly at :05 (offset from the TimescaleDB
    -- continuous-aggregate refreshes that run on the hour).
    IF NOT EXISTS (SELECT 1 FROM cron.job WHERE jobname = 'refresh_mv_tenant_risk_summary') THEN
        PERFORM cron.schedule(
            'refresh_mv_tenant_risk_summary',
            '5 * * * *',
            'REFRESH MATERIALIZED VIEW CONCURRENTLY mv_tenant_risk_summary'
        );
    END IF;

    -- Ensure current + next month usage_record partitions exist. Daily at
    -- 02:10 UTC; the V004 insert trigger remains as the fallback.
    IF NOT EXISTS (SELECT 1 FROM cron.job WHERE jobname = 'create_usage_partitions') THEN
        PERFORM cron.schedule(
            'create_usage_partitions',
            '10 2 * * *',
            'SELECT create_next_usage_partition()'
        );
    END IF;

    -- Sweep expired idempotency keys (V011 defines expires_at + a btree
    -- index on it, which this delete uses). Every 15 min.
    -- At high scale, batch the delete; at current scale it is bounded and
    -- index-supported.
    IF NOT EXISTS (SELECT 1 FROM cron.job WHERE jobname = 'cleanup_idempotency_keys') THEN
        PERFORM cron.schedule(
            'cleanup_idempotency_keys',
            '*/15 * * * *',
            'DELETE FROM idempotency_key WHERE expires_at < now()'
        );
    END IF;

    -- INTENTIONALLY NOT SCHEDULED: audit_log archival. Enable only after the
    -- S3 export pipeline exists and archive_audit_log() is activated:
    --
    -- IF NOT EXISTS (SELECT 1 FROM cron.job WHERE jobname = 'archive_audit_log') THEN
    --     PERFORM cron.schedule(
    --         'archive_audit_log',
    --         '0 3 1 * *',               -- monthly, 1st day at 03:00 UTC
    --         'SELECT archive_audit_log(730)'
    --     );
    -- END IF;
END $$;
