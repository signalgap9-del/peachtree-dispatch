-- V012: Materialized views for reporting dashboards

----------------------------------------------------------------------
-- Workspace usage summary: usage per workspace per feature per day
-- Joins usage_record through tenant_member -> workspace_member -> workspace
----------------------------------------------------------------------
CREATE MATERIALIZED VIEW mv_workspace_usage_summary AS
SELECT
    w.id          AS workspace_id,
    w.name        AS workspace_name,
    ur.tenant_id,
    ur.feature,
    ur.usage_date,
    SUM(ur.count) AS total_usage
FROM usage_record ur
JOIN tenant_member tm ON tm.tenant_id = ur.tenant_id
JOIN workspace_member wm ON wm.member_id = tm.id
JOIN workspace w ON w.id = wm.workspace_id
GROUP BY w.id, w.name, ur.tenant_id, ur.feature, ur.usage_date
WITH NO DATA;

CREATE UNIQUE INDEX uq_mv_workspace_usage
    ON mv_workspace_usage_summary (workspace_id, feature, usage_date);

CREATE INDEX idx_mv_workspace_usage_tenant
    ON mv_workspace_usage_summary (tenant_id, usage_date DESC);

----------------------------------------------------------------------
-- Tenant risk summary: average risk per tenant per day from observations
----------------------------------------------------------------------
CREATE MATERIALIZED VIEW mv_tenant_risk_summary AS
SELECT
    sr.member_id,
    tm.tenant_id,
    (rro.time AT TIME ZONE 'UTC')::date AS observation_date,
    AVG(rro.risk_score)::numeric(5,2)   AS avg_risk_score,
    MAX(rro.risk_score)                 AS max_risk_score,
    MIN(rro.risk_score)                 AS min_risk_score,
    COUNT(*)                            AS observation_count
FROM route_risk_observation rro
JOIN saved_route sr ON sr.id = rro.saved_route_id
JOIN tenant_member tm ON tm.id = sr.member_id
GROUP BY tm.tenant_id, sr.member_id, observation_date
WITH NO DATA;

CREATE UNIQUE INDEX uq_mv_tenant_risk
    ON mv_tenant_risk_summary (tenant_id, member_id, observation_date);

CREATE INDEX idx_mv_tenant_risk_date
    ON mv_tenant_risk_summary (tenant_id, observation_date DESC);
