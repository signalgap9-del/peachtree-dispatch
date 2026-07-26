-- V011: Idempotency keys with TTL and API keys

CREATE TABLE idempotency_key (
    key_hash     TEXT NOT NULL,
    tenant_id    UUID NOT NULL,
    operation    TEXT NOT NULL,
    resource_id  UUID,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL DEFAULT (now() + INTERVAL '24 hours'),
    PRIMARY KEY (tenant_id, operation, key_hash)
);

-- TTL cleanup index: serves `DELETE ... WHERE expires_at < now()`.
-- Plain btree (a partial predicate on now() is illegal: now() is STABLE, not IMMUTABLE).
CREATE INDEX idx_idempotency_key_expires
    ON idempotency_key (expires_at);

-- Lookup by tenant + operation
CREATE INDEX idx_idempotency_key_tenant_op
    ON idempotency_key (tenant_id, operation, created_at DESC);

CREATE TABLE api_key (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    member_id     UUID REFERENCES tenant_member(id) ON DELETE SET NULL,
    key_hash      TEXT UNIQUE NOT NULL,
    name          TEXT,
    last_used_at  TIMESTAMPTZ,
    expires_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_api_key_tenant ON api_key (tenant_id);
CREATE INDEX idx_api_key_member ON api_key (member_id) WHERE member_id IS NOT NULL;

-- Expired API keys cleanup index (plain btree; partial now() predicate is illegal).
CREATE INDEX idx_api_key_expired
    ON api_key (expires_at)
    WHERE expires_at IS NOT NULL;

-- RLS for tables created in this migration
ALTER TABLE idempotency_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE idempotency_key FORCE ROW LEVEL SECURITY;

CREATE POLICY idempotency_key_isolation ON idempotency_key
    USING (tenant_id = current_tenant_id());

ALTER TABLE api_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE api_key FORCE ROW LEVEL SECURITY;

CREATE POLICY api_key_isolation ON api_key
    USING (tenant_id = current_tenant_id());
