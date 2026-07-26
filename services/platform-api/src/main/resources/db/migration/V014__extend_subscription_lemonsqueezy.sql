-- V014: Extend subscription table with Lemon Squeezy billing columns

ALTER TABLE subscription
    ADD COLUMN lemonsqueezy_subscription_id TEXT,
    ADD COLUMN lemonsqueezy_customer_id     TEXT,
    ADD COLUMN lemonsqueezy_variant_id      TEXT,
    ADD COLUMN billing_status               TEXT
        CHECK (billing_status IN ('active', 'on_trial', 'paused', 'past_due', 'cancelled', 'expired')),
    ADD COLUMN billing_period_end           TIMESTAMPTZ,
    ADD COLUMN cancel_at_period_end         BOOLEAN NOT NULL DEFAULT false;

CREATE UNIQUE INDEX uq_subscription_ls_id
    ON subscription (lemonsqueezy_subscription_id)
    WHERE lemonsqueezy_subscription_id IS NOT NULL;

CREATE INDEX idx_subscription_ls_customer
    ON subscription (lemonsqueezy_customer_id)
    WHERE lemonsqueezy_customer_id IS NOT NULL;
