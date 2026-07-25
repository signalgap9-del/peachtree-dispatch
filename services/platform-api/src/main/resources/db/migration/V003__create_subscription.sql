-- V003: Subscription (temporal) and Entitlement tables

CREATE TABLE subscription (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    plan                  TEXT NOT NULL DEFAULT 'FREE',
    status                TEXT NOT NULL DEFAULT 'TRIAL'
                          CHECK (status IN ('TRIAL', 'ACTIVE', 'PAST_DUE', 'GRACE', 'CANCELLED')),
    current_period_start  DATE,
    current_period_end    DATE,
    valid_from            TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_to              TIMESTAMPTZ NOT NULL DEFAULT 'infinity',
    version               INT NOT NULL DEFAULT 1,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Temporal constraint: only one active subscription per tenant
CREATE UNIQUE INDEX uq_subscription_tenant_active
    ON subscription (tenant_id)
    WHERE valid_to = 'infinity';

CREATE INDEX idx_subscription_tenant_status
    ON subscription (tenant_id, status);

CREATE TABLE entitlement (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id    UUID NOT NULL REFERENCES subscription(id) ON DELETE CASCADE,
    feature            TEXT NOT NULL,
    quota_limit        INT NOT NULL DEFAULT 0,
    saved_route_limit  INT NOT NULL DEFAULT 5,
    saved_place_limit  INT NOT NULL DEFAULT 5
);

CREATE INDEX idx_entitlement_subscription_id ON entitlement (subscription_id);
CREATE UNIQUE INDEX uq_entitlement_sub_feature ON entitlement (subscription_id, feature);
