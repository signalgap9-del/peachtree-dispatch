CREATE EXTENSION IF NOT EXISTS postgis;
-- statement
CREATE TABLE IF NOT EXISTS app_user (
  user_id UUID PRIMARY KEY,
  auth_subject VARCHAR(255) NOT NULL UNIQUE,
  email VARCHAR(320),
  display_name VARCHAR(120),
  home_time_zone VARCHAR(64) NOT NULL DEFAULT 'America/New_York',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at TIMESTAMPTZ
);
-- statement
CREATE TABLE IF NOT EXISTS saved_item (
  saved_item_id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES app_user(user_id) ON DELETE CASCADE,
  item_type VARCHAR(24) NOT NULL CHECK (item_type IN ('PLACE', 'ROUTE', 'CORRIDOR')),
  name VARCHAR(160) NOT NULL,
  point GEOGRAPHY(POINT, 4326),
  path GEOGRAPHY(LINESTRING, 4326),
  metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  current_risk_score SMALLINT CHECK (current_risk_score BETWEEN 0 AND 100),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at TIMESTAMPTZ,
  CHECK (
    (item_type = 'PLACE' AND point IS NOT NULL AND path IS NULL) OR
    (item_type IN ('ROUTE', 'CORRIDOR') AND point IS NULL AND path IS NOT NULL)
  )
);
-- statement
CREATE INDEX IF NOT EXISTS saved_item_user_updated_idx
  ON saved_item(user_id, updated_at DESC)
  WHERE deleted_at IS NULL;
-- statement
CREATE INDEX IF NOT EXISTS saved_item_point_gist_idx
  ON saved_item USING GIST(point)
  WHERE point IS NOT NULL AND deleted_at IS NULL;
-- statement
CREATE INDEX IF NOT EXISTS saved_item_path_gist_idx
  ON saved_item USING GIST(path)
  WHERE path IS NOT NULL AND deleted_at IS NULL;
-- statement
CREATE TABLE IF NOT EXISTS saved_collection (
  collection_id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES app_user(user_id) ON DELETE CASCADE,
  name VARCHAR(120) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(user_id, name)
);
-- statement
CREATE TABLE IF NOT EXISTS saved_collection_item (
  collection_id UUID NOT NULL REFERENCES saved_collection(collection_id) ON DELETE CASCADE,
  saved_item_id UUID NOT NULL REFERENCES saved_item(saved_item_id) ON DELETE CASCADE,
  position INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(collection_id, saved_item_id)
);
-- statement
CREATE TABLE IF NOT EXISTS alert_subscription (
  subscription_id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES app_user(user_id) ON DELETE CASCADE,
  saved_item_id UUID REFERENCES saved_item(saved_item_id) ON DELETE CASCADE,
  minimum_risk_score SMALLINT NOT NULL DEFAULT 55 CHECK (minimum_risk_score BETWEEN 0 AND 100),
  hazard_categories TEXT[] NOT NULL DEFAULT '{}',
  channels TEXT[] NOT NULL DEFAULT '{IN_APP}',
  enabled BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- statement
CREATE INDEX IF NOT EXISTS alert_subscription_enabled_idx
  ON alert_subscription(saved_item_id, minimum_risk_score)
  WHERE enabled = true;
-- statement
CREATE TABLE IF NOT EXISTS route_plan (
  route_plan_id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES app_user(user_id) ON DELETE CASCADE,
  origin GEOGRAPHY(POINT, 4326) NOT NULL,
  destination GEOGRAPHY(POINT, 4326) NOT NULL,
  selected_path GEOGRAPHY(LINESTRING, 4326),
  vehicle_type VARCHAR(24) NOT NULL,
  risk_score SMALLINT CHECK (risk_score BETWEEN 0 AND 100),
  model_version VARCHAR(64),
  request JSONB NOT NULL,
  result JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ
);
-- statement
CREATE INDEX IF NOT EXISTS route_plan_user_created_idx
  ON route_plan(user_id, created_at DESC);
-- statement
CREATE INDEX IF NOT EXISTS route_plan_selected_path_gist_idx
  ON route_plan USING GIST(selected_path)
  WHERE selected_path IS NOT NULL;
-- statement
CREATE TABLE IF NOT EXISTS risk_exposure (
  risk_exposure_id UUID PRIMARY KEY,
  route_plan_id UUID REFERENCES route_plan(route_plan_id) ON DELETE CASCADE,
  saved_item_id UUID REFERENCES saved_item(saved_item_id) ON DELETE CASCADE,
  hazard_category VARCHAR(64) NOT NULL,
  hazard_event_id VARCHAR(255),
  hazard_geometry GEOGRAPHY(MULTIPOLYGON, 4326),
  overlap_path GEOGRAPHY(LINESTRING, 4326),
  overlap_distance_miles NUMERIC(10, 2) CHECK (overlap_distance_miles >= 0),
  risk_score SMALLINT NOT NULL CHECK (risk_score BETWEEN 0 AND 100),
  source VARCHAR(80) NOT NULL,
  model_version VARCHAR(64),
  observed_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ,
  metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (route_plan_id IS NOT NULL OR saved_item_id IS NOT NULL)
);
-- statement
CREATE INDEX IF NOT EXISTS risk_exposure_route_score_idx
  ON risk_exposure(route_plan_id, risk_score DESC)
  WHERE route_plan_id IS NOT NULL;
-- statement
CREATE INDEX IF NOT EXISTS risk_exposure_saved_item_score_idx
  ON risk_exposure(saved_item_id, risk_score DESC)
  WHERE saved_item_id IS NOT NULL;
-- statement
CREATE INDEX IF NOT EXISTS risk_exposure_hazard_geometry_gist_idx
  ON risk_exposure USING GIST(hazard_geometry)
  WHERE hazard_geometry IS NOT NULL;
-- statement
CREATE INDEX IF NOT EXISTS risk_exposure_overlap_path_gist_idx
  ON risk_exposure USING GIST(overlap_path)
  WHERE overlap_path IS NOT NULL;
