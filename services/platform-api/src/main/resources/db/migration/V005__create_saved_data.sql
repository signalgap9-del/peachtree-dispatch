-- V005: Saved Route and Saved Place with soft-delete support

CREATE TABLE saved_route (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id         UUID NOT NULL REFERENCES tenant_member(id) ON DELETE CASCADE,
    workspace_id      UUID REFERENCES workspace(id) ON DELETE SET NULL,
    name              TEXT NOT NULL,
    origin_name       TEXT,
    destination_name  TEXT,
    vehicle_type      TEXT NOT NULL DEFAULT 'car',
    risk_threshold    INT NOT NULL DEFAULT 55 CHECK (risk_threshold BETWEEN 0 AND 100),
    monitor_enabled   BOOLEAN NOT NULL DEFAULT true,
    deleted_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE saved_place (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id     UUID NOT NULL REFERENCES tenant_member(id) ON DELETE CASCADE,
    workspace_id  UUID REFERENCES workspace(id) ON DELETE SET NULL,
    name          TEXT NOT NULL,
    latitude      DOUBLE PRECISION CHECK (latitude BETWEEN -90 AND 90),
    longitude     DOUBLE PRECISION CHECK (longitude BETWEEN -180 AND 180),
    deleted_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Partial unique: prevent duplicate active routes per member
CREATE UNIQUE INDEX uq_saved_route_member_name_active
    ON saved_route (member_id, name)
    WHERE deleted_at IS NULL;

-- Partial unique: prevent duplicate active places per member
CREATE UNIQUE INDEX uq_saved_place_member_name_active
    ON saved_place (member_id, name)
    WHERE deleted_at IS NULL;

-- Query indexes for active records
CREATE INDEX idx_saved_route_member_active
    ON saved_route (member_id, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_saved_route_workspace
    ON saved_route (workspace_id)
    WHERE deleted_at IS NULL AND workspace_id IS NOT NULL;

CREATE INDEX idx_saved_route_monitor
    ON saved_route (member_id)
    WHERE deleted_at IS NULL AND monitor_enabled = true;

CREATE INDEX idx_saved_place_member_active
    ON saved_place (member_id, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_saved_place_workspace
    ON saved_place (workspace_id)
    WHERE deleted_at IS NULL AND workspace_id IS NOT NULL;

-- Spatial index on saved_place coordinates (btree for point queries)
CREATE INDEX idx_saved_place_coords
    ON saved_place (latitude, longitude)
    WHERE deleted_at IS NULL;

CREATE TRIGGER trg_saved_route_updated_at
    BEFORE UPDATE ON saved_route
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();
