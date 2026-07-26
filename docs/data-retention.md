# Data Retention & GDPR Policy

This document is the operational source of truth for what FreightScaler
stores, for how long, and how it is deleted. It executes the archival and
retention decisions in [ADR-0020](adr/0020-database-scale-sharding.md) and
the isolation model in [ADR-0021](adr/0021-multi-tenancy-isolation.md).
Retention windows are enforced by database mechanisms where possible and by
documented runbook steps where automation does not exist yet.

---

## 1. PII inventory

What we store that is personal data under GDPR, and where:

| Data | Table.Column | Category | Protection |
| --- | --- | --- | --- |
| Email address | `tenant_member.email` (+ `email_hash`) | Direct identifier | Plaintext retained for login lookup, plus a deterministic SHA-256 `email_hash` generated column with a unique partial index (V016, pgcrypto `digest(lower(email), 'sha256')`) - equality lookups can move to the hash without a schema change. Full at-rest encryption (`pgp_sym_encrypt` with a KMS-held key) is documented as future work in V016. Access: RLS-scoped to the member's tenant. |
| Display name | `tenant_member.display_name` | Direct identifier | Plaintext; RLS-scoped. |
| Home/work locations | `saved_place.latitude/longitude`, `saved_place.name` | Sensitive location data | RLS-scoped; partial btree index on coordinates. Location data reveals habits and is treated as the highest-sensitivity tier we hold. |
| Route endpoints | `saved_route.origin_name`, `destination_name` | Indirect location data | RLS-scoped. |
| Billing references | `subscription.lemonsqueezy_customer_id`, `lemonsqueezy_subscription_id` | Pseudonymous billing ID | Lemon Squeezy is merchant of record; we store only their IDs, never card data. |
| API key material | `api_key.key_hash` | Credential | **Irreversible hash only** (V011); plaintext is shown once at creation and never persisted. pgcrypto (`V001`) provides `gen_random_uuid()` identifiers. |
| Idempotency keys | `idempotency_key.key_hash` | Credential-adjacent | Irreversible hash; 24 h TTL (`expires_at`), swept by the V017 cron job. |
| Derived row images | `audit_log.changes` (JSONB `to_jsonb(OLD/NEW)`) | **Derived PII** - may embed email, names, coordinates of the audited row | RLS-scoped; see erasure procedure (section 3.2) for scrubbing. |
| Embeddings | `route_risk_observation.embedding`, `alert_event.embedding` | Derived, non-identifying | 384-dim vectors from risk factors/alert text; treated as non-PII but deleted with their source rows. |

We do **not** store: passwords (Google OAuth, ADR-level design in
`google-auth.md`), client IP addresses, payment card data, or special
category data beyond what location data implies.

Cross-cutting protections: RLS with `FORCE` on every tenant-scoped table
(V009/V011, see ADR-0021); TLS in transit; volume/RDS encryption at rest;
secrets never in the database except as hashes.

---

## 2. Retention periods

| Category | Tables | Online retention | Cold retention | Mechanism | Status |
| --- | --- | --- | --- | --- | --- |
| Risk observations | `route_risk_observation` | 90 days (0-30d uncompressed, 30-90d compressed) | 2 years (S3 parquet) | TimescaleDB retention policy drops chunks > 90d (V007); compression policy > 30d (V017); S3 export before drop | Drop + compression live; S3 export pipeline TBD |
| Risk aggregates | `cagg_risk_hourly`, `cagg_risk_daily`, `mv_tenant_risk_summary` | Derived from online window; daily aggregates kept | Rebuilt from cold if required | Continuous aggregate refresh policies (V007); MVIEW refresh (V017) | Live |
| Usage metering | `usage_record` | 13 months (monthly partitions) | 7 years (billing evidence) | Detach + export partitions > 13 months | Partitions live (V004 + V017 proactive creation); archival is a runbook step |
| Audit trail | `audit_log` | 2 years (target: monthly partitions) | 7 years (SOC 2 / tax-style hold) | No-op archive stub in V017; activates with S3 pipeline | **Gap tracked in ADR-0020** |
| Alert history | `alert_event`, `alert_escalation` | 2 years | Archived with audit | Cascade from `alert_event`; archival runbook | Manual until archive pipeline |
| Saved routes/places | `saved_route`, `saved_place` | Account lifetime; soft-deleted rows hard-purged 30 days after `deleted_at` | None | Purge job over `deleted_at < now() - 30d` (runbook, then cron) | Manual |
| Idempotency keys | `idempotency_key` | 24 hours | None | `expires_at` TTL; V017 cron sweeper every 15 min | Live (V017) |
| Account & membership | `tenant`, `tenant_member`, `workspace*` | Account lifetime | None | Erasure on request (section 3) | Live |
| Subscription/billing | `subscription`, `entitlement` | Account lifetime + 7 years for invoicing evidence after cancellation | 7 years cold | Ledger-style keep; Lemon Squeezy holds the authoritative invoice | Live |

Legal bases, stated plainly: observations and alerts are processed for
contract performance (the service the user bought); usage records for
contract + legal obligation (billing/tax); audit logs for legitimate interest
(security and abuse investigation) with PII minimized per section 3.2;
account data for contract; marketing none (we do not store marketing lists).

---

## 3. Right to erasure (Article 17)

### 3.1 Member-level erasure

Triggered by a user deleting their account or a DSAR. Executed as one
transaction against the primary, with `app.tenant_id` set so RLS stays
active for verification queries. Order matters - children first:

```sql
BEGIN;
-- 0. Resolve identity
-- :member_id, :tenant_id from the request (verified out-of-band)

-- 1. Observations: NO FK to saved_route exists (V007), so cascade will NOT
--    remove them. Delete explicitly.
DELETE FROM route_risk_observation
WHERE saved_route_id IN (SELECT id FROM saved_route WHERE member_id = :member_id);

-- 2. Embedding provenance rows for those observations and the member's alerts
--    (record_id format: '<saved_route_id>:<time>' for observations,
--    '<alert_id>' for alert_event - see EmbeddingService.recordMetadata)
DELETE FROM embedding_metadata
WHERE (table_name = 'route_risk_observation'
       AND split_part(record_id, ':', 1)::uuid IN
           (SELECT id FROM saved_route WHERE member_id = :member_id))
   OR (table_name = 'alert_event'
       AND record_id::uuid IN
           (SELECT ae.id FROM alert_event ae
            JOIN saved_route sr ON sr.id = ae.saved_route_id
            WHERE sr.member_id = :member_id));

-- 3. Alerts cascade to escalations (FK ON DELETE CASCADE), but the route FK
--    is ON DELETE SET NULL - delete by route instead to remove the events.
DELETE FROM alert_event
WHERE saved_route_id IN (SELECT id FROM saved_route WHERE member_id = :member_id);

-- 4. API keys: FK to member is ON DELETE SET NULL, which would keep the key
--    row. Delete explicitly.
DELETE FROM api_key WHERE member_id = :member_id;

-- 5. Audit log: two sanctioned modes.
--    (a) Default - minimize PII, keep the operational trail (legitimate
--        interest): null the actor and scrub identifying fields from the
--        JSONB row images.
UPDATE audit_log
SET member_id = NULL,
    changes = changes - 'email' - 'display_name' - 'latitude' - 'longitude'
WHERE member_id = :member_id;
--    (b) On explicit request where no legal hold applies:
-- DELETE FROM audit_log WHERE member_id = :member_id;

-- 6. The member row itself. saved_route/saved_place/workspace_member cascade
--    via FK ON DELETE CASCADE; idempotency keys are tenant-scoped and expire
--    within 24 h regardless.
DELETE FROM tenant_member WHERE id = :member_id;

-- 7. Refresh read models so aggregates stop reflecting the erased data.
REFRESH MATERIALIZED VIEW CONCURRENTLY mv_workspace_usage_summary;
REFRESH MATERIALIZED VIEW CONCURRENTLY mv_tenant_risk_summary;
COMMIT;
```

Notes:

- Steps 1 and 4 exist because two schema relationships do **not** cascade
  the way erasure needs (`route_risk_observation` has no FK; `api_key` uses
  `ON DELETE SET NULL`). Any schema change to these tables must keep this
  procedure correct.
- The FK cascades in steps 3 and 6 are index-backed: V015 (performance
  indexes) added full (non-partial) FK indexes on `saved_route.member_id`,
  `saved_place.member_id`, `tenant_member.tenant_id`, and
  `alert_escalation.target_member_id` specifically so cascade deletes do not
  seq-scan soft-deleted rows. Erasure stays fast as tables grow.
- Soft-deleted rows (`deleted_at`) are erased by the same path; the 30-day
  hard-purge window (section 2) is the maximum time a soft-deleted route's
  observations survive without an explicit request.
- Log the erasure event itself to a compliance-only trail (who/when/what
  scope, never the erased content).

### 3.2 Tenant-level erasure

When an entire tenant (customer) exercises erasure:

1. Run the observation/embedding/alert deletes in 3.1 scoped by the tenant's
   routes (`saved_route` joined through `tenant_member.tenant_id`).
2. `DELETE FROM tenant WHERE id = :tenant_id` - FK cascades remove members,
   workspaces, subscriptions, entitlements, `usage_record`, `audit_log`,
   `alert_event`, `api_key` (V001-V008 FK graph).
3. Refresh both materialized views.
4. Record the event; retain only the billing evidence required by law
   (invoice IDs held by Lemon Squeezy; our `subscription` rows cascade -
   export the billing ledger columns first if a legal hold applies).

### 3.3 Erasure vs derived and cold data

- **Materialized views / continuous aggregates:** refreshed in the same
  procedure; stale aggregates are never the system of record.
- **Cold S3 archives:** erasure propagates at the next archive rebuild or on
  demand via a runbook delete of the tenant/member prefix. Archives are
  partitioned by `tenant_id` in their S3 key layout specifically so one
  tenant's data is deletable without rewriting the dataset.
- **Backups:** see section 4 - erased data ages out of the backup window;
  this is the accepted industry practice under GDPR Recital considerations
  (backups are not "processed" for other purposes and have a bounded TTL).

---

## 4. Backup & PITR retention

| Environment | Mechanism | Backup retention | PITR window |
| --- | --- | --- | --- |
| Local dev (Docker) | Named volumes only; no automated backup | None | None |
| Production (RDS/Aurora target) | Automated snapshots + WAL archiving | 7 days default, configurable to 35 | Any second within the retention window |
| Cold archives (S3) | Lifecycle to Glacier after 1 year; object lock on the 7-year billing/audit prefixes | Per category (section 2) | N/A |

Operational rules:

- Snapshots are encrypted with KMS; restore drills quarterly (runbook:
  `docs/runbooks/` - restore drill entry to be added with the RDS cutover).
- A deletion is immediately effective in the live database and effective in
  the backup chain when the containing snapshot expires (<= 35 days). DSAR
  responses state this explicitly.
- Cross-region snapshot copy inherits the same retention and the same
  erasure-age-out semantics.

---

## 5. Subject access requests (Article 15)

Export one tenant's data with `COPY` of the RLS-scoped row set: `tenant`,
`tenant_member`, `workspace*`, `subscription`, `entitlement`, `usage_record`,
`saved_route`, `saved_place`, `alert_event`, `alert_escalation`,
`route_risk_observation` (via the member's routes), and `audit_log` rows
where `tenant_id` matches. Delivered as JSON/CSV within 30 days. API keys
are never exportable (hash-only).

---

## 6. Ownership and review

- This policy is reviewed whenever a migration adds a table holding PII; the
  migration PR must update section 1 and section 2.
- Retention windows change only via PR to this file plus the enforcing
  mechanism (cron job / TimescaleDB policy) in the same change.
- Open items tracked in ADR-0020: S3 cold-export pipeline, `audit_log`
  partitioning, `saved_route`/`saved_place` hard-purge automation.
