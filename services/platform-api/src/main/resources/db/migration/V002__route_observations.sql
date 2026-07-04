CREATE TABLE IF NOT EXISTS route_observation (
  route_observation_id UUID PRIMARY KEY,
  saved_item_id UUID NOT NULL REFERENCES saved_item(saved_item_id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES app_user(user_id) ON DELETE CASCADE,
  observed_at TIMESTAMPTZ NOT NULL,
  planned_duration_minutes NUMERIC(10, 2) NOT NULL CHECK (planned_duration_minutes > 0),
  actual_duration_minutes NUMERIC(10, 2) NOT NULL CHECK (actual_duration_minutes > 0),
  delay_minutes NUMERIC(10, 2) NOT NULL,
  observed_risk_score SMALLINT NOT NULL CHECK (observed_risk_score BETWEEN 0 AND 100),
  metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- statement
CREATE INDEX IF NOT EXISTS route_observation_saved_item_observed_idx
  ON route_observation(saved_item_id, observed_at DESC);
-- statement
CREATE INDEX IF NOT EXISTS route_observation_user_observed_idx
  ON route_observation(user_id, observed_at DESC);
