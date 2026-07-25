-- pgbench custom script: alert event creation and state transition
-- Simulates INSERT alert_event + transition to NOTIFIED
-- Run via: pgbench -U atmospath -d atmospath -f 03_alert_trigger.sql -c 50 -T 60

\set risk_score random(50, 100)
\set severity_idx random(1, 3)

SELECT CASE :severity_idx
    WHEN 1 THEN 'LOW'
    WHEN 2 THEN 'MEDIUM'
    ELSE 'HIGH'
END AS severity \gset

BEGIN;

INSERT INTO alert_event (id, route_id, risk_score, severity, status, created_at, updated_at)
VALUES (
    gen_random_uuid(),
    (SELECT id FROM saved_route ORDER BY random() LIMIT 1),
    :risk_score,
    :'severity',
    'TRIGGERED',
    now(),
    now()
);

UPDATE alert_event
SET status = 'NOTIFIED', updated_at = now()
WHERE id = (SELECT id FROM alert_event ORDER BY created_at DESC LIMIT 1);

COMMIT;
