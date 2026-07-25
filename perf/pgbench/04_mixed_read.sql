-- pgbench custom script: mixed read workload
-- Simulates typical dashboard reads: saved routes + risk observations + usage
-- Run via: pgbench -U atmospath -d atmospath -f 04_mixed_read.sql -c 100 -T 60

\set tenant_offset random(1, 100)

SELECT id AS tenant_id FROM tenant WHERE slug = 'load-test-' || :tenant_offset \gset

-- Read saved routes for a member of this tenant
SELECT sr.*
FROM saved_route sr
JOIN tenant_member tm ON sr.user_id = tm.id
WHERE tm.tenant_id = :'tenant_id'::uuid
ORDER BY sr.created_at DESC
LIMIT 20;

-- Read risk observations for those routes
SELECT rro.*
FROM route_risk_observation rro
JOIN saved_route sr ON rro.saved_route_id = sr.id
JOIN tenant_member tm ON sr.user_id = tm.id
WHERE tm.tenant_id = :'tenant_id'::uuid
ORDER BY rro.checked_at DESC
LIMIT 50;

-- Read current usage for this tenant
SELECT feature, used_count, usage_date
FROM usage_record
WHERE tenant_id = :'tenant_id'::uuid
  AND usage_date = CURRENT_DATE;
