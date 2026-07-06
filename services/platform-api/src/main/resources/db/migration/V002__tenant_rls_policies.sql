CREATE SCHEMA IF NOT EXISTS app;
-- statement
CREATE OR REPLACE FUNCTION app.current_user_id()
RETURNS uuid
LANGUAGE sql
STABLE
AS $$
  SELECT NULLIF(current_setting('app.user_id', true), '')::uuid
$$;
-- statement
ALTER TABLE app_user ENABLE ROW LEVEL SECURITY;
-- statement
ALTER TABLE app_user FORCE ROW LEVEL SECURITY;
-- statement
DROP POLICY IF EXISTS app_user_own_profile ON app_user;
-- statement
CREATE POLICY app_user_own_profile ON app_user
  USING (user_id = app.current_user_id())
  WITH CHECK (user_id = app.current_user_id());
-- statement
ALTER TABLE saved_item ENABLE ROW LEVEL SECURITY;
-- statement
ALTER TABLE saved_item FORCE ROW LEVEL SECURITY;
-- statement
DROP POLICY IF EXISTS saved_item_owner_access ON saved_item;
-- statement
CREATE POLICY saved_item_owner_access ON saved_item
  USING (user_id = app.current_user_id())
  WITH CHECK (user_id = app.current_user_id());
-- statement
ALTER TABLE saved_collection ENABLE ROW LEVEL SECURITY;
-- statement
ALTER TABLE saved_collection FORCE ROW LEVEL SECURITY;
-- statement
DROP POLICY IF EXISTS saved_collection_owner_access ON saved_collection;
-- statement
CREATE POLICY saved_collection_owner_access ON saved_collection
  USING (user_id = app.current_user_id())
  WITH CHECK (user_id = app.current_user_id());
-- statement
ALTER TABLE saved_collection_item ENABLE ROW LEVEL SECURITY;
-- statement
ALTER TABLE saved_collection_item FORCE ROW LEVEL SECURITY;
-- statement
DROP POLICY IF EXISTS saved_collection_item_owner_access ON saved_collection_item;
-- statement
CREATE POLICY saved_collection_item_owner_access ON saved_collection_item
  USING (
    EXISTS (
      SELECT 1
      FROM saved_collection c
      WHERE c.collection_id = saved_collection_item.collection_id
        AND c.user_id = app.current_user_id()
    )
    AND EXISTS (
      SELECT 1
      FROM saved_item i
      WHERE i.saved_item_id = saved_collection_item.saved_item_id
        AND i.user_id = app.current_user_id()
    )
  )
  WITH CHECK (
    EXISTS (
      SELECT 1
      FROM saved_collection c
      WHERE c.collection_id = saved_collection_item.collection_id
        AND c.user_id = app.current_user_id()
    )
    AND EXISTS (
      SELECT 1
      FROM saved_item i
      WHERE i.saved_item_id = saved_collection_item.saved_item_id
        AND i.user_id = app.current_user_id()
    )
  );
-- statement
ALTER TABLE alert_subscription ENABLE ROW LEVEL SECURITY;
-- statement
ALTER TABLE alert_subscription FORCE ROW LEVEL SECURITY;
-- statement
DROP POLICY IF EXISTS alert_subscription_owner_access ON alert_subscription;
-- statement
CREATE POLICY alert_subscription_owner_access ON alert_subscription
  USING (
    user_id = app.current_user_id()
    AND (
      saved_item_id IS NULL
      OR EXISTS (
        SELECT 1
        FROM saved_item i
        WHERE i.saved_item_id = alert_subscription.saved_item_id
          AND i.user_id = app.current_user_id()
      )
    )
  )
  WITH CHECK (
    user_id = app.current_user_id()
    AND (
      saved_item_id IS NULL
      OR EXISTS (
        SELECT 1
        FROM saved_item i
        WHERE i.saved_item_id = alert_subscription.saved_item_id
          AND i.user_id = app.current_user_id()
      )
    )
  );
-- statement
ALTER TABLE route_plan ENABLE ROW LEVEL SECURITY;
-- statement
ALTER TABLE route_plan FORCE ROW LEVEL SECURITY;
-- statement
DROP POLICY IF EXISTS route_plan_owner_access ON route_plan;
-- statement
CREATE POLICY route_plan_owner_access ON route_plan
  USING (user_id = app.current_user_id())
  WITH CHECK (user_id = app.current_user_id());
-- statement
ALTER TABLE risk_exposure ENABLE ROW LEVEL SECURITY;
-- statement
ALTER TABLE risk_exposure FORCE ROW LEVEL SECURITY;
-- statement
DROP POLICY IF EXISTS risk_exposure_owner_access ON risk_exposure;
-- statement
CREATE POLICY risk_exposure_owner_access ON risk_exposure
  USING (
    (
      saved_item_id IS NOT NULL
      AND EXISTS (
        SELECT 1
        FROM saved_item i
        WHERE i.saved_item_id = risk_exposure.saved_item_id
          AND i.user_id = app.current_user_id()
      )
    )
    OR (
      route_plan_id IS NOT NULL
      AND EXISTS (
        SELECT 1
        FROM route_plan r
        WHERE r.route_plan_id = risk_exposure.route_plan_id
          AND r.user_id = app.current_user_id()
      )
    )
  )
  WITH CHECK (
    (
      saved_item_id IS NOT NULL
      AND EXISTS (
        SELECT 1
        FROM saved_item i
        WHERE i.saved_item_id = risk_exposure.saved_item_id
          AND i.user_id = app.current_user_id()
      )
    )
    OR (
      route_plan_id IS NOT NULL
      AND EXISTS (
        SELECT 1
        FROM route_plan r
        WHERE r.route_plan_id = risk_exposure.route_plan_id
          AND r.user_id = app.current_user_id()
      )
    )
  );
