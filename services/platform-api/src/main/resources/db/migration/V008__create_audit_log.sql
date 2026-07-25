-- V008: Audit log with automatic trigger-based recording

CREATE TABLE audit_log (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     UUID,
    member_id     UUID,
    action        TEXT NOT NULL,
    resource_type TEXT NOT NULL,
    resource_id   UUID,
    changes       JSONB,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_log_tenant_time
    ON audit_log (tenant_id, created_at DESC);

CREATE INDEX idx_audit_log_resource
    ON audit_log (resource_type, resource_id, created_at DESC);

CREATE INDEX idx_audit_log_member
    ON audit_log (member_id, created_at DESC)
    WHERE member_id IS NOT NULL;

-- Generic audit trigger function
-- Reads app.tenant_id and app.member_id from session settings
CREATE OR REPLACE FUNCTION audit_trigger_fn()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_tenant_id  UUID;
    v_member_id  UUID;
    v_action     TEXT;
    v_resource_id UUID;
    v_changes    JSONB;
BEGIN
    -- Extract session context (set by application layer)
    BEGIN
        v_tenant_id := NULLIF(current_setting('app.tenant_id', true), '')::uuid;
    EXCEPTION WHEN OTHERS THEN
        v_tenant_id := NULL;
    END;

    BEGIN
        v_member_id := NULLIF(current_setting('app.member_id', true), '')::uuid;
    EXCEPTION WHEN OTHERS THEN
        v_member_id := NULL;
    END;

    v_action := TG_OP;

    IF TG_OP = 'DELETE' THEN
        v_resource_id := OLD.id;
        v_changes := jsonb_build_object('old', to_jsonb(OLD));
    ELSIF TG_OP = 'INSERT' THEN
        v_resource_id := NEW.id;
        v_changes := jsonb_build_object('new', to_jsonb(NEW));
    ELSE -- UPDATE
        v_resource_id := NEW.id;
        v_changes := jsonb_build_object('old', to_jsonb(OLD), 'new', to_jsonb(NEW));
    END IF;

    INSERT INTO audit_log (tenant_id, member_id, action, resource_type, resource_id, changes)
    VALUES (v_tenant_id, v_member_id, v_action, TG_TABLE_NAME, v_resource_id, v_changes);

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    ELSE
        RETURN NEW;
    END IF;
END;
$$;

-- Attach audit triggers to key tables
CREATE TRIGGER trg_audit_saved_route
    AFTER INSERT OR UPDATE OR DELETE ON saved_route
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_fn();

CREATE TRIGGER trg_audit_saved_place
    AFTER INSERT OR UPDATE OR DELETE ON saved_place
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_fn();

CREATE TRIGGER trg_audit_subscription
    AFTER INSERT OR UPDATE OR DELETE ON subscription
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_fn();

CREATE TRIGGER trg_audit_alert_event
    AFTER INSERT OR UPDATE OR DELETE ON alert_event
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_fn();
