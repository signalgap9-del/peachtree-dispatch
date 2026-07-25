-- V004: Usage Record partitioned by RANGE(usage_date)
-- Monthly partitions for 2026 + auto-partition trigger for future months

CREATE TABLE usage_record (
    id          UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL,
    feature     TEXT NOT NULL,
    usage_date  DATE NOT NULL,
    count       INT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, feature, usage_date, id)
) PARTITION BY RANGE (usage_date);

-- Monthly partitions for 2026
CREATE TABLE usage_record_2026_01 PARTITION OF usage_record
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
CREATE TABLE usage_record_2026_02 PARTITION OF usage_record
    FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');
CREATE TABLE usage_record_2026_03 PARTITION OF usage_record
    FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');
CREATE TABLE usage_record_2026_04 PARTITION OF usage_record
    FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');
CREATE TABLE usage_record_2026_05 PARTITION OF usage_record
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');
CREATE TABLE usage_record_2026_06 PARTITION OF usage_record
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
CREATE TABLE usage_record_2026_07 PARTITION OF usage_record
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE usage_record_2026_08 PARTITION OF usage_record
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE usage_record_2026_09 PARTITION OF usage_record
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
CREATE TABLE usage_record_2026_10 PARTITION OF usage_record
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE usage_record_2026_11 PARTITION OF usage_record
    FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE usage_record_2026_12 PARTITION OF usage_record
    FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');

-- Composite index for quota-check queries
CREATE INDEX idx_usage_record_tenant_feature_date
    ON usage_record (tenant_id, feature, usage_date);

-- Auto-partition function: creates next month's partition on demand
CREATE OR REPLACE FUNCTION create_usage_partition_if_missing()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    partition_name TEXT;
    month_start    DATE;
    month_end      DATE;
BEGIN
    month_start := date_trunc('month', NEW.usage_date)::date;
    month_end   := (month_start + INTERVAL '1 month')::date;
    partition_name := 'usage_record_' || to_char(month_start, 'YYYY_MM');

    IF NOT EXISTS (
        SELECT 1 FROM pg_class WHERE relname = partition_name
    ) THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF usage_record FOR VALUES FROM (%L) TO (%L)',
            partition_name, month_start, month_end
        );
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_usage_record_auto_partition
    BEFORE INSERT ON usage_record
    FOR EACH ROW
    EXECUTE FUNCTION create_usage_partition_if_missing();
