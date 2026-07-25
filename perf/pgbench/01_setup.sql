-- pgbench seed data for SaaS data layer load testing
-- Run: pgbench -U atmospath -d atmospath -f 01_setup.sql
-- Requires: SaaS schema migrations applied (V001+ with subscription, alert, quota tables)

-- Seed 100 tenants
INSERT INTO tenant (id, name, slug)
SELECT
    gen_random_uuid(),
    'Load Test Tenant ' || i,
    'load-test-' || i
FROM generate_series(1, 100) AS i
ON CONFLICT (slug) DO NOTHING;

-- Seed 500 members across tenants (5 per tenant)
INSERT INTO tenant_member (id, tenant_id, email, display_name, role)
SELECT
    gen_random_uuid(),
    t.id,
    'member-' || row_number() OVER () || '@loadtest.atmospath.dev',
    'Load Member ' || row_number() OVER (),
    CASE (row_number() OVER (PARTITION BY t.id) % 5)
        WHEN 0 THEN 'OWNER'
        WHEN 1 THEN 'ADMIN'
        ELSE 'MEMBER'
    END
FROM tenant t, generate_series(1, 5)
WHERE t.slug LIKE 'load-test-%'
ON CONFLICT DO NOTHING;

-- Seed 1000 saved routes (10 per tenant)
-- Assumes saved_route table exists from V001 migration
INSERT INTO saved_route (id, user_id, name, origin_name, destination_name, travel_mode,
                         distance_miles, duration_minutes, risk_score, weather_risk_score,
                         geometry, departure_time)
SELECT
    gen_random_uuid(),
    tm.id,
    'Route ' || row_number() OVER (),
    'Origin ' || (row_number() OVER () % 50),
    'Destination ' || (row_number() OVER () % 50),
    'CAR',
    50 + (random() * 400)::int,
    30 + (random() * 300)::int,
    (random() * 100)::int,
    (random() * 100)::int,
    ST_MakeLine(
        ST_MakePoint(-84.0 + random() * 10, 30.0 + random() * 10),
        ST_MakePoint(-84.0 + random() * 10, 30.0 + random() * 10)
    ),
    now() - (random() * interval '30 days')
FROM tenant_member tm
CROSS JOIN generate_series(1, 2)
WHERE tm.role = 'OWNER'
ON CONFLICT DO NOTHING;

-- Seed usage records for quota testing
INSERT INTO usage_record (id, tenant_id, feature, usage_date, used_count)
SELECT
    gen_random_uuid(),
    t.id,
    feature.f,
    CURRENT_DATE,
    (random() * 20)::int
FROM tenant t
CROSS JOIN (VALUES ('ROUTE_PLAN'), ('PLACE_SEARCH'), ('LOCATION_RISK'), ('ALERT_SEARCH')) AS feature(f)
WHERE t.slug LIKE 'load-test-%'
ON CONFLICT (tenant_id, feature, usage_date) DO UPDATE
    SET used_count = EXCLUDED.used_count;

-- Seed subscription records
INSERT INTO subscription (id, tenant_id, plan, status, current_period_start, current_period_end, version)
SELECT
    gen_random_uuid(),
    t.id,
    CASE (row_number() OVER () % 3)
        WHEN 0 THEN 'FREE'
        WHEN 1 THEN 'PRO'
        ELSE 'TEAM'
    END,
    'ACTIVE',
    now() - interval '15 days',
    now() + interval '15 days',
    1
FROM tenant t
WHERE t.slug LIKE 'load-test-%'
ON CONFLICT (tenant_id) DO NOTHING;
