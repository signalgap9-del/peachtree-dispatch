-- V009: Row-Level Security on all tenant-scoped tables
-- Application must SET app.tenant_id = '<uuid>' per connection/transaction

-- Helper: extract current tenant from session
CREATE OR REPLACE FUNCTION current_tenant_id()
RETURNS UUID
LANGUAGE sql
STABLE
AS $$
    SELECT NULLIF(current_setting('app.tenant_id', true), '')::uuid
$$;

-- tenant: members can see their own tenant
ALTER TABLE tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON tenant
    USING (id = current_tenant_id());

-- tenant_member
ALTER TABLE tenant_member ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_member FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_member_isolation ON tenant_member
    USING (tenant_id = current_tenant_id());

-- workspace
ALTER TABLE workspace ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace FORCE ROW LEVEL SECURITY;

CREATE POLICY workspace_isolation ON workspace
    USING (tenant_id = current_tenant_id());

-- workspace_member (via workspace join)
ALTER TABLE workspace_member ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_member FORCE ROW LEVEL SECURITY;

CREATE POLICY workspace_member_isolation ON workspace_member
    USING (
        EXISTS (
            SELECT 1 FROM workspace w
            WHERE w.id = workspace_member.workspace_id
              AND w.tenant_id = current_tenant_id()
        )
    );

-- subscription
ALTER TABLE subscription ENABLE ROW LEVEL SECURITY;
ALTER TABLE subscription FORCE ROW LEVEL SECURITY;

CREATE POLICY subscription_isolation ON subscription
    USING (tenant_id = current_tenant_id());

-- entitlement (via subscription join)
ALTER TABLE entitlement ENABLE ROW LEVEL SECURITY;
ALTER TABLE entitlement FORCE ROW LEVEL SECURITY;

CREATE POLICY entitlement_isolation ON entitlement
    USING (
        EXISTS (
            SELECT 1 FROM subscription s
            WHERE s.id = entitlement.subscription_id
              AND s.tenant_id = current_tenant_id()
        )
    );

-- usage_record
ALTER TABLE usage_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE usage_record FORCE ROW LEVEL SECURITY;

CREATE POLICY usage_record_isolation ON usage_record
    USING (tenant_id = current_tenant_id());

-- saved_route (via tenant_member join)
ALTER TABLE saved_route ENABLE ROW LEVEL SECURITY;
ALTER TABLE saved_route FORCE ROW LEVEL SECURITY;

CREATE POLICY saved_route_isolation ON saved_route
    USING (
        EXISTS (
            SELECT 1 FROM tenant_member tm
            WHERE tm.id = saved_route.member_id
              AND tm.tenant_id = current_tenant_id()
        )
    );

-- saved_place (via tenant_member join)
ALTER TABLE saved_place ENABLE ROW LEVEL SECURITY;
ALTER TABLE saved_place FORCE ROW LEVEL SECURITY;

CREATE POLICY saved_place_isolation ON saved_place
    USING (
        EXISTS (
            SELECT 1 FROM tenant_member tm
            WHERE tm.id = saved_place.member_id
              AND tm.tenant_id = current_tenant_id()
        )
    );

-- alert_event
ALTER TABLE alert_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE alert_event FORCE ROW LEVEL SECURITY;

CREATE POLICY alert_event_isolation ON alert_event
    USING (tenant_id = current_tenant_id());

-- alert_escalation (via alert_event join)
ALTER TABLE alert_escalation ENABLE ROW LEVEL SECURITY;
ALTER TABLE alert_escalation FORCE ROW LEVEL SECURITY;

CREATE POLICY alert_escalation_isolation ON alert_escalation
    USING (
        EXISTS (
            SELECT 1 FROM alert_event ae
            WHERE ae.id = alert_escalation.alert_event_id
              AND ae.tenant_id = current_tenant_id()
        )
    );

-- audit_log
ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log FORCE ROW LEVEL SECURITY;

CREATE POLICY audit_log_isolation ON audit_log
    USING (tenant_id = current_tenant_id());
-- NOTE: RLS for idempotency_key and api_key is applied in V011
-- after those tables are created.
