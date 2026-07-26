-- V016: Domain constraints, temporal exclusion, generated columns, and PII hardening
--
-- Purpose: push data-integrity guarantees down into the database so data stays
-- correct even when application code has bugs. Everything here is ADDITIVE:
-- no column is dropped, no existing query is broken, and every constraint is
-- written to accept the full range of legitimate existing data.
--
-- Scope note: V015 (indexes) and V017 (operational) are owned separately.
-- This migration touches neither.
--
-- Contents:
--   A. CHECK constraints      -- per-row domain rules
--   B. EXCLUSION constraint   -- row-spanning temporal invariant (subscriptions)
--   C. Generated columns      -- DB-computed derived fields
--   D. PII hardening          -- deterministic email_hash for lookups (pgcrypto)
--   E. NOT NULL discipline    -- tighten a nullable-with-default column

----------------------------------------------------------------------
-- Extensions
----------------------------------------------------------------------
-- btree_gist: provides a GiST operator class for scalar types (uuid, etc.)
-- so we can combine `tenant_id WITH =` with a range overlap in one exclusion
-- constraint. Required before the subscription exclusion constraint below.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- pgcrypto: provides digest() used by the email_hash generated column.
-- First enabled in V001; re-guarded here so this migration is self-contained
-- and idempotent.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

----------------------------------------------------------------------
-- A. CHECK constraints (per-row domain rules)
----------------------------------------------------------------------

-- subscription: billing period must not run backwards.
-- Uses >= (not >) because prorate_subscription() can legitimately produce a
-- zero-length period when a plan change lands on the same day the current
-- period ends (new current_period_start = CURRENT_DATE = old period end).
-- A strictly-positive-length check would reject that legitimate case; the
-- invariant we actually must protect is "end never precedes start".
ALTER TABLE subscription
    ADD CONSTRAINT chk_subscription_period_order
    CHECK (current_period_end >= current_period_start);

-- subscription: temporal-version validity must not run backwards.
-- Uses >= because now() returns the transaction start timestamp; closing a
-- row in the same transaction that created it yields valid_to = valid_from.
ALTER TABLE subscription
    ADD CONSTRAINT chk_subscription_validity_order
    CHECK (valid_to >= valid_from);

-- subscription: version is a monotonic counter starting at 1.
ALTER TABLE subscription
    ADD CONSTRAINT chk_subscription_version_positive
    CHECK (version >= 1);

-- entitlement: limits are never negative. quota_limit = 0 means "unlimited"
-- (see check_and_consume_quota), so 0 must remain allowed.
ALTER TABLE entitlement
    ADD CONSTRAINT chk_entitlement_quota_limit_nonneg
    CHECK (quota_limit >= 0);

ALTER TABLE entitlement
    ADD CONSTRAINT chk_entitlement_saved_route_limit_nonneg
    CHECK (saved_route_limit >= 0);

ALTER TABLE entitlement
    ADD CONSTRAINT chk_entitlement_saved_place_limit_nonneg
    CHECK (saved_place_limit >= 0);

-- usage_record: usage can never be negative.
-- usage_record is PARTITION BY RANGE; a CHECK on the parent is inherited by
-- every existing and future partition, so this covers all months at once.
ALTER TABLE usage_record
    ADD CONSTRAINT chk_usage_record_count_nonneg
    CHECK (count >= 0);

-- alert_event: state-machine timestamps are monotonic relative to trigger time.
-- An alert cannot be notified / escalated / acknowledged / resolved before it
-- was triggered. transition_alert_state() stamps each with now() at transition
-- time, so these always hold for app-written data; the checks guard against
-- manual backdating or out-of-order writes. NULL timestamps pass (CHECK is
-- NULL-safe), so partially-progressed alerts are unaffected.
ALTER TABLE alert_event
    ADD CONSTRAINT chk_alert_event_notified_after_trigger
    CHECK (notified_at >= triggered_at);

ALTER TABLE alert_event
    ADD CONSTRAINT chk_alert_event_escalated_after_trigger
    CHECK (escalated_at >= triggered_at);

ALTER TABLE alert_event
    ADD CONSTRAINT chk_alert_event_acknowledged_after_trigger
    CHECK (acknowledged_at >= triggered_at);

ALTER TABLE alert_event
    ADD CONSTRAINT chk_alert_event_resolved_after_trigger
    CHECK (resolved_at >= triggered_at);

-- idempotency_key: the TTL must expire strictly after the key was created.
ALTER TABLE idempotency_key
    ADD CONSTRAINT chk_idempotency_key_ttl_positive
    CHECK (expires_at > created_at);

-- api_key: if an expiry is set, it must be after creation. NULL expiry means
-- "never expires" and is allowed.
ALTER TABLE api_key
    ADD CONSTRAINT chk_api_key_expiry_after_creation
    CHECK (expires_at IS NULL OR expires_at > created_at);

-- embedding_metadata: model version is a positive counter.
ALTER TABLE embedding_metadata
    ADD CONSTRAINT chk_embedding_metadata_version_positive
    CHECK (embedding_version >= 1);

----------------------------------------------------------------------
-- C. Generated columns (DB-computed derived fields)
--    (Placed before the exclusion constraint for readable ordering; the
--     exclusion does not depend on these columns.)
----------------------------------------------------------------------

-- subscription.is_active: single source of truth for "serviceable / quota-active".
-- Mirrors the exact status set used by check_and_consume_quota()
-- (TRIAL, ACTIVE, GRACE). PAST_DUE and CANCELLED are treated as inactive so
-- quota is blocked for them. Centralizing the definition here stops every
-- query from re-deriving the status set by hand. The expression is IMMUTABLE
-- (plain equality comparisons), so it is valid for a STORED generated column.
ALTER TABLE subscription
    ADD COLUMN is_active BOOLEAN
    GENERATED ALWAYS AS (status IN ('TRIAL', 'ACTIVE', 'GRACE')) STORED;

----------------------------------------------------------------------
-- B. EXCLUSION constraint (row-spanning temporal invariant)
----------------------------------------------------------------------

-- Invariant: a tenant must never have two NON-CANCELLED subscription versions
-- whose validity windows [valid_from, valid_to) overlap.
--
-- This is the temporal integrity of the versioned subscription model. The
-- prorate_subscription() flow closes the old version (valid_to = now()) and
-- opens the new one (valid_from = now()) as adjacent, non-overlapping ranges,
-- so legitimate lifecycle operations pass. A buggy or manual path that opens a
-- second active version without closing the first is rejected here.
--
-- Design choices / tradeoffs:
--   * Partial (WHERE status <> 'CANCELLED'): matches the "active periods only"
--     requirement. Cancelled versions drop out of the check, so historical
--     cancelled rows can never block a new subscription and cannot cause this
--     constraint to reject pre-existing data. The safe default direction for an
--     exclusion is "participate unless explicitly cancelled", so any future
--     status added to the CHECK list is automatically covered.
--   * tstzrange bounds '[)' (inclusive start, exclusive end): adjacent ranges
--     [t0,t1) and [t1,inf) do NOT overlap, which is exactly what the prorate
--     hand-off produces.
--   * Complementary, not redundant, with uq_subscription_tenant_active
--     (WHERE valid_to = 'infinity'): that index forbids two "current" rows for
--     a tenant across ALL statuses; this exclusion forbids overlapping active
--     windows even when neither row is at infinity. Both are kept.
--   * RLS note: constraint validation scans the table as the migrating role.
--     Migrations run as the table owner / superuser, which bypasses RLS, so the
--     full table is validated (FORCE ROW LEVEL SECURITY does not apply to
--     superusers).
ALTER TABLE subscription
    ADD CONSTRAINT excl_subscription_tenant_active_validity
    EXCLUDE USING gist (
        tenant_id WITH =,
        tstzrange(valid_from, valid_to, '[)') WITH &&
    )
    WHERE (status <> 'CANCELLED');

----------------------------------------------------------------------
-- D. PII hardening: deterministic email_hash for lookups (pgcrypto)
----------------------------------------------------------------------
--
-- tenant_member.email is PII. Today the app looks members up by plaintext
-- email (TenantMemberRepository.findByTenantIdAndEmailAndDeletedAtIsNull).
-- This change is ADDITIVE: the plaintext column and every existing query are
-- preserved untouched. We add a deterministic, indexed hash so equality
-- lookups ("does this email already belong to this tenant?") can be answered
-- without scanning or comparing plaintext, and so a future migration can move
-- lookups off the raw column without a schema change.
--
--   email_hash = SHA-256(lower(email))
--
-- Lowercasing first makes the hash case-insensitive, matching how email
-- equality is normally treated. digest() and lower() are both IMMUTABLE, so
-- the hash is a valid STORED generated column that stays in sync automatically.
--
-- Full at-rest encryption (future work, NOT done here because it changes the
-- column type and breaks direct equality queries): store the email ciphertext
-- via pgcrypto pgp_sym_encrypt(email, key) and look up by email_hash. The
-- symmetric key must live OUTSIDE the database (e.g., AWS KMS / Secrets
-- Manager, injected at runtime) -- never in the schema or a table. Until then,
-- rely on RDS encryption-at-rest + RLS (V009) for the plaintext column, and use
-- email_hash for equality probes.
ALTER TABLE tenant_member
    ADD COLUMN email_hash BYTEA
    GENERATED ALWAYS AS (digest(lower(email), 'sha256')) STORED;

-- Unique partial index mirroring uq_tenant_member_active_email, but keyed on
-- the hash: one active membership per email per tenant, enforceable and
-- queryable without touching plaintext. Partial (deleted_at IS NULL) so
-- soft-deleted rows do not block re-invites, matching existing behavior.
CREATE UNIQUE INDEX uq_tenant_member_active_email_hash
    ON tenant_member (tenant_id, email_hash)
    WHERE deleted_at IS NULL;

----------------------------------------------------------------------
-- E. NOT NULL discipline
----------------------------------------------------------------------

-- embedding_metadata.created_at has DEFAULT now() but was left nullable, so a
-- row could still be inserted with an explicit NULL. Backfill any such rows
-- first (safe: only touches NULLs), then tighten to NOT NULL.
UPDATE embedding_metadata
   SET created_at = now()
 WHERE created_at IS NULL;

ALTER TABLE embedding_metadata
    ALTER COLUMN created_at SET NOT NULL;
