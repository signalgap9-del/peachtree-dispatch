-- V006: Alert Event (state machine) and Alert Escalation

CREATE TABLE alert_event (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    saved_route_id  UUID REFERENCES saved_route(id) ON DELETE SET NULL,
    tenant_id       UUID NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    state           TEXT NOT NULL DEFAULT 'TRIGGERED'
                    CHECK (state IN ('TRIGGERED', 'NOTIFIED', 'ESCALATED', 'ACKNOWLEDGED', 'RESOLVED')),
    severity        TEXT NOT NULL DEFAULT 'MODERATE'
                    CHECK (severity IN ('LOW', 'MODERATE', 'HIGH', 'SEVERE')),
    risk_score      INT CHECK (risk_score BETWEEN 0 AND 100),
    triggered_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    notified_at     TIMESTAMPTZ,
    escalated_at    TIMESTAMPTZ,
    acknowledged_at TIMESTAMPTZ,
    resolved_at     TIMESTAMPTZ,
    triggered_by    TEXT NOT NULL DEFAULT 'SYSTEM'
);

CREATE TABLE alert_escalation (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    alert_event_id    UUID NOT NULL REFERENCES alert_event(id) ON DELETE CASCADE,
    level             INT NOT NULL CHECK (level > 0),
    target_member_id  UUID REFERENCES tenant_member(id) ON DELETE SET NULL,
    notified_at       TIMESTAMPTZ,
    responded_at      TIMESTAMPTZ
);

-- Query patterns: open alerts per tenant, alerts per route
CREATE INDEX idx_alert_event_tenant_state
    ON alert_event (tenant_id, state)
    WHERE state NOT IN ('RESOLVED');

CREATE INDEX idx_alert_event_route
    ON alert_event (saved_route_id, triggered_at DESC)
    WHERE saved_route_id IS NOT NULL;

CREATE INDEX idx_alert_event_severity
    ON alert_event (tenant_id, severity, triggered_at DESC)
    WHERE state IN ('TRIGGERED', 'NOTIFIED', 'ESCALATED');

CREATE INDEX idx_alert_escalation_event
    ON alert_escalation (alert_event_id, level);

CREATE INDEX idx_alert_escalation_pending
    ON alert_escalation (target_member_id, level)
    WHERE responded_at IS NULL;
