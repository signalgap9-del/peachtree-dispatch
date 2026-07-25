-- V001: Tenant and Tenant Member tables
-- Multi-tenant identity foundation

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE tenant (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        TEXT NOT NULL,
    slug        TEXT UNIQUE NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tenant_member (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    email         TEXT NOT NULL,
    display_name  TEXT,
    role          TEXT NOT NULL DEFAULT 'MEMBER'
                  CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER')),
    deleted_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Partial unique: one active membership per email per tenant
CREATE UNIQUE INDEX uq_tenant_member_active_email
    ON tenant_member (tenant_id, email)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_tenant_member_tenant_id
    ON tenant_member (tenant_id)
    WHERE deleted_at IS NULL;

-- updated_at trigger for tenant
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_tenant_updated_at
    BEFORE UPDATE ON tenant
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_tenant_member_updated_at
    BEFORE UPDATE ON tenant_member
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();
