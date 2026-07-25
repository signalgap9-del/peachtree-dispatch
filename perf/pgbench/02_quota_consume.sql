-- pgbench custom script: quota consumption via stored function
-- Simulates check_and_consume_quota(tenant_id, feature) calls
-- Run via: pgbench -U atmospath -d atmospath -f 02_quota_consume.sql -c 100 -T 60

\set tenant_offset random(1, 100)
\set feature_idx random(1, 4)

SELECT id AS tenant_id FROM tenant WHERE slug = 'load-test-' || :tenant_offset \gset

SELECT CASE :feature_idx
    WHEN 1 THEN 'ROUTE_PLAN'
    WHEN 2 THEN 'PLACE_SEARCH'
    WHEN 3 THEN 'LOCATION_RISK'
    ELSE 'ALERT_SEARCH'
END AS feature \gset

SELECT check_and_consume_quota(:'tenant_id'::uuid, :'feature');
