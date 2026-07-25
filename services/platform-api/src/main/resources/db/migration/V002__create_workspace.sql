-- V002: Workspace and Workspace Member tables

CREATE TABLE workspace (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    name        TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE workspace_member (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id  UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    member_id     UUID NOT NULL REFERENCES tenant_member(id) ON DELETE CASCADE,
    role          TEXT NOT NULL DEFAULT 'VIEWER'
                  CHECK (role IN ('VIEWER', 'EDITOR', 'ADMIN')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, member_id)
);

CREATE INDEX idx_workspace_tenant_id ON workspace (tenant_id);
CREATE INDEX idx_workspace_member_member_id ON workspace_member (member_id);

CREATE TRIGGER trg_workspace_updated_at
    BEFORE UPDATE ON workspace
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();
