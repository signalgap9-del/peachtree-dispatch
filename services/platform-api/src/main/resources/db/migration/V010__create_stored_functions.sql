-- V010: Stored functions for quota, alert state machine, RBAC, and proration

----------------------------------------------------------------------
-- 1. check_and_consume_quota(tenant_id, feature)
--    Atomically checks entitlement quota and increments usage_record.
--    Returns TRUE if quota available and consumed, FALSE otherwise.
----------------------------------------------------------------------
CREATE OR REPLACE FUNCTION check_and_consume_quota(
    p_tenant_id UUID,
    p_feature   TEXT
)
RETURNS BOOLEAN
LANGUAGE plpgsql
AS $$
DECLARE
    v_quota_limit INT;
    v_current     INT;
    v_today       DATE := CURRENT_DATE;
BEGIN
    -- Lock the active entitlement row for this feature
    SELECT e.quota_limit
      INTO v_quota_limit
      FROM entitlement e
      JOIN subscription s ON s.id = e.subscription_id
     WHERE s.tenant_id = p_tenant_id
       AND s.valid_to = 'infinity'
       AND s.status IN ('TRIAL', 'ACTIVE', 'GRACE')
       AND e.feature = p_feature
     ORDER BY s.created_at DESC
     LIMIT 1
     FOR UPDATE;

    -- No entitlement found means no quota restriction (allow)
    IF v_quota_limit IS NULL THEN
        RETURN TRUE;
    END IF;

    -- quota_limit = 0 means unlimited
    IF v_quota_limit = 0 THEN
        -- Still record usage
        INSERT INTO usage_record (tenant_id, feature, usage_date, count)
        VALUES (p_tenant_id, p_feature, v_today, 1)
        ON CONFLICT (tenant_id, feature, usage_date, id) DO NOTHING;
        RETURN TRUE;
    END IF;

    -- Check current usage today (lock the row)
    SELECT COALESCE(sum(count), 0)
      INTO v_current
      FROM usage_record
     WHERE tenant_id = p_tenant_id
       AND feature = p_feature
       AND usage_date = v_today
     FOR UPDATE;

    IF v_current >= v_quota_limit THEN
        RETURN FALSE;
    END IF;

    -- Consume: upsert usage count
    INSERT INTO usage_record (tenant_id, feature, usage_date, count)
    VALUES (p_tenant_id, p_feature, v_today, 1)
    ON CONFLICT (tenant_id, feature, usage_date, id) DO NOTHING;

    -- Increment existing row if present
    UPDATE usage_record
       SET count = count + 1
     WHERE tenant_id = p_tenant_id
       AND feature = p_feature
       AND usage_date = v_today;

    RETURN TRUE;
END;
$$;

----------------------------------------------------------------------
-- 2. transition_alert_state(event_id, new_state)
--    Enforces legal state transitions for alert_event.
--    Legal transitions:
--      TRIGGERED    -> NOTIFIED, ACKNOWLEDGED, RESOLVED
--      NOTIFIED     -> ESCALATED, ACKNOWLEDGED, RESOLVED
--      ESCALATED    -> ACKNOWLEDGED, RESOLVED
--      ACKNOWLEDGED -> RESOLVED
--      RESOLVED     -> (terminal)
----------------------------------------------------------------------
CREATE OR REPLACE FUNCTION transition_alert_state(
    p_event_id  UUID,
    p_new_state TEXT
)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_current_state TEXT;
    v_valid         BOOLEAN := FALSE;
BEGIN
    SELECT state INTO v_current_state
      FROM alert_event
     WHERE id = p_event_id
     FOR UPDATE;

    IF v_current_state IS NULL THEN
        RAISE EXCEPTION 'alert_event % not found', p_event_id;
    END IF;

    -- Validate transition
    v_valid := CASE
        WHEN v_current_state = 'TRIGGERED'
            THEN p_new_state IN ('NOTIFIED', 'ACKNOWLEDGED', 'RESOLVED')
        WHEN v_current_state = 'NOTIFIED'
            THEN p_new_state IN ('ESCALATED', 'ACKNOWLEDGED', 'RESOLVED')
        WHEN v_current_state = 'ESCALATED'
            THEN p_new_state IN ('ACKNOWLEDGED', 'RESOLVED')
        WHEN v_current_state = 'ACKNOWLEDGED'
            THEN p_new_state = 'RESOLVED'
        ELSE FALSE
    END;

    IF NOT v_valid THEN
        RAISE EXCEPTION 'Illegal alert state transition: % -> %', v_current_state, p_new_state;
    END IF;

    -- Apply transition with timestamp
    UPDATE alert_event SET
        state = p_new_state,
        notified_at     = CASE WHEN p_new_state = 'NOTIFIED'     THEN now() ELSE notified_at END,
        escalated_at    = CASE WHEN p_new_state = 'ESCALATED'    THEN now() ELSE escalated_at END,
        acknowledged_at = CASE WHEN p_new_state = 'ACKNOWLEDGED' THEN now() ELSE acknowledged_at END,
        resolved_at     = CASE WHEN p_new_state = 'RESOLVED'     THEN now() ELSE resolved_at END
    WHERE id = p_event_id;
END;
$$;

----------------------------------------------------------------------
-- 3. has_permission(member_id, resource_id, action)
--    RBAC with inheritance: workspace role grants access to resources
--    in that workspace. Tenant-level ADMIN/OWNER has full access.
--    Actions: 'read', 'write', 'delete', 'admin'
----------------------------------------------------------------------
CREATE OR REPLACE FUNCTION has_permission(
    p_member_id   UUID,
    p_resource_id UUID,
    p_action      TEXT
)
RETURNS BOOLEAN
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    v_tenant_role TEXT;
    v_ws_role     TEXT;
BEGIN
    -- Get tenant-level role
    SELECT tm.role INTO v_tenant_role
      FROM tenant_member tm
     WHERE tm.id = p_member_id
       AND tm.deleted_at IS NULL;

    IF v_tenant_role IS NULL THEN
        RETURN FALSE;
    END IF;

    -- Tenant OWNER and ADMIN have full access to all resources
    IF v_tenant_role IN ('OWNER', 'ADMIN') THEN
        RETURN TRUE;
    END IF;

    -- Check workspace-level role for the resource
    -- Resources can be saved_route or saved_place with workspace_id
    SELECT wm.role INTO v_ws_role
      FROM workspace_member wm
      JOIN saved_route sr ON sr.workspace_id = wm.workspace_id
     WHERE wm.member_id = p_member_id
       AND sr.id = p_resource_id
       AND sr.deleted_at IS NULL
    UNION ALL
    SELECT wm.role
      FROM workspace_member wm
      JOIN saved_place sp ON sp.workspace_id = wm.workspace_id
     WHERE wm.member_id = p_member_id
       AND sp.id = p_resource_id
       AND sp.deleted_at IS NULL
    LIMIT 1;

    IF v_ws_role IS NULL THEN
        -- Check if member owns the resource directly
        IF EXISTS (
            SELECT 1 FROM saved_route WHERE id = p_resource_id AND member_id = p_member_id AND deleted_at IS NULL
        ) OR EXISTS (
            SELECT 1 FROM saved_place WHERE id = p_resource_id AND member_id = p_member_id AND deleted_at IS NULL
        ) THEN
            RETURN TRUE;  -- Owner has full access to own resources
        END IF;
        RETURN FALSE;
    END IF;

    -- Workspace role hierarchy: ADMIN > EDITOR > VIEWER
    RETURN CASE
        WHEN p_action = 'read'   THEN v_ws_role IN ('VIEWER', 'EDITOR', 'ADMIN')
        WHEN p_action = 'write'  THEN v_ws_role IN ('EDITOR', 'ADMIN')
        WHEN p_action = 'delete' THEN v_ws_role = 'ADMIN'
        WHEN p_action = 'admin'  THEN v_ws_role = 'ADMIN'
        ELSE FALSE
    END;
END;
$$;

----------------------------------------------------------------------
-- 4. prorate_subscription(subscription_id, new_plan)
--    Calculates date-proportional credit for remaining days on current
--    period and creates a new subscription version.
----------------------------------------------------------------------
CREATE OR REPLACE FUNCTION prorate_subscription(
    p_subscription_id UUID,
    p_new_plan        TEXT
)
RETURNS JSONB
LANGUAGE plpgsql
AS $$
DECLARE
    v_sub            subscription%ROWTYPE;
    v_days_total     INT;
    v_days_remaining INT;
    v_credit_ratio   NUMERIC(5,4);
    v_new_id         UUID := gen_random_uuid();
BEGIN
    SELECT * INTO v_sub
      FROM subscription
     WHERE id = p_subscription_id
     FOR UPDATE;

    IF v_sub.id IS NULL THEN
        RAISE EXCEPTION 'subscription % not found', p_subscription_id;
    END IF;

    IF v_sub.current_period_start IS NULL OR v_sub.current_period_end IS NULL THEN
        RAISE EXCEPTION 'subscription % has no active billing period', p_subscription_id;
    END IF;

    v_days_total     := v_sub.current_period_end - v_sub.current_period_start;
    v_days_remaining := v_sub.current_period_end - CURRENT_DATE;

    IF v_days_total <= 0 THEN
        v_credit_ratio := 0;
    ELSE
        v_credit_ratio := GREATEST(v_days_remaining::numeric / v_days_total::numeric, 0);
    END IF;

    -- Close current subscription version
    UPDATE subscription
       SET valid_to = now(),
           status   = 'CANCELLED'
     WHERE id = p_subscription_id;

    -- Create new subscription version with prorated period
    INSERT INTO subscription (id, tenant_id, plan, status, current_period_start, current_period_end, valid_from, version)
    VALUES (
        v_new_id,
        v_sub.tenant_id,
        p_new_plan,
        'ACTIVE',
        CURRENT_DATE,
        v_sub.current_period_end,
        now(),
        v_sub.version + 1
    );

    RETURN jsonb_build_object(
        'new_subscription_id', v_new_id,
        'previous_plan', v_sub.plan,
        'new_plan', p_new_plan,
        'credit_ratio', v_credit_ratio,
        'days_remaining', v_days_remaining,
        'days_total', v_days_total
    );
END;
$$;
