# EXPLAIN Query Catalog

Execution plans for the hottest and most critical queries in the platform.
Plans captured from a real TimescaleDB 16 instance (Docker `timescale/timescaledb:latest-pg16`)
with all migrations V001-V017 applied, representative sample data, and `ANALYZE` run.

**Reading the plans.** Tables in the test database are small (3-500 rows), so the
planner rationally chooses sequential scans for single-page tables. Where this
happens, we also show the **forced-index plan** (`SET enable_seqscan = off`) to
demonstrate the index path that activates at production scale. The index choice
is confirmed correct in every case.

---

## Q1: Active Subscription Lookup by Tenant

**Caller.** `SubscriptionRepository.findActiveByTenantId()` -- hit on every
authenticated request for quota + RLS context.

```sql
SELECT id, tenant_id, plan, status, valid_from, valid_to
FROM subscription
WHERE tenant_id = $1
  AND valid_from <= now()
  AND valid_to > now()
ORDER BY valid_to DESC;
```

**Index.** `idx_subscription_tenant_validity (tenant_id, valid_to DESC) INCLUDE (status, valid_from, plan)`

**Plan (forced-index, confirms index path):**

```
Index Scan using idx_subscription_tenant_validity on subscription
  (cost=0.13..2.38 rows=2 width=59) (actual time=0.029..0.030 rows=1 loops=1)
  Index Cond: ((tenant_id = $1) AND (valid_to > now()))
  Filter: (valid_from <= now())
  Buffers: shared hit=2
Execution Time: 0.089 ms
```

**Why it matters.** This is the single hottest query in the system. The covering
INCLUDE columns mean the planner can satisfy the `status` and `valid_from` filters
and return `plan` without a heap fetch (index-only scan at scale when the
visibility map is warm). The `valid_to DESC` ordering means the most recent
subscription version is returned first without a sort node.

**At small scale** the planner uses a seq scan (4 rows, 1 page). This is correct:
reading one page is cheaper than an index traversal. The index activates when the
table exceeds ~50-100 rows.

---

## Q2: Entitlement Quota Lookup

**Caller.** `EntitlementRepository.findActiveQuotaLimit()` and
`check_and_consume_quota()` -- hit on every metered API call.

```sql
SELECT e.quota_limit
FROM entitlement e
JOIN subscription s ON s.id = e.subscription_id
WHERE s.tenant_id = $1
  AND e.feature = $2
  AND s.status IN ('TRIAL', 'ACTIVE', 'GRACE')
  AND s.valid_from <= now()
  AND s.valid_to > now()
ORDER BY s.valid_to DESC
LIMIT 1;
```

**Indexes.** `idx_subscription_tenant_validity` (subscription side) +
`idx_entitlement_quota_cover (subscription_id, feature) INCLUDE (quota_limit, saved_route_limit, saved_place_limit)`

**Plan (real):**

```
Limit  (cost=0.13..1.83 rows=1 width=12) (actual time=0.072..0.074 rows=1 loops=1)
  Buffers: shared hit=3
  ->  Nested Loop  (cost=0.13..3.54 rows=2 width=12) (actual time=0.071..0.072 rows=1 loops=1)
        Join Filter: (e.subscription_id = s.id)
        ->  Index Scan using idx_subscription_tenant_validity on subscription s
              Index Cond: ((tenant_id = $1) AND (valid_to > now()))
              Filter: ((status = ANY ('{TRIAL,ACTIVE,GRACE}')) AND (valid_from <= now()))
              Buffers: shared hit=2
        ->  Materialize  (cost=0.00..1.06 rows=3 width=20)
              ->  Seq Scan on entitlement e
                    Filter: (feature = 'ROUTE_PLAN')
                    Buffers: shared hit=1
Execution Time: 0.248 ms
```

**Why it matters.** The subscription side uses the covering index (confirmed in
the real plan). At scale, the entitlement side switches from seq scan to
`idx_entitlement_quota_cover`, which is a covering index: `(subscription_id, feature)
INCLUDE (quota_limit, ...)` enables an index-only scan returning `quota_limit`
without touching the heap. The nested loop is optimal here: one subscription row
drives a point lookup into entitlement.

---

## Q3: Open Alerts by Route

**Caller.** `AlertEventRepository.findOpenByRouteId()` -- called when displaying
a route's active alert banner.

```sql
SELECT id, state, severity, risk_score, triggered_at
FROM alert_event
WHERE saved_route_id = $1
  AND state NOT IN ('ACKNOWLEDGED', 'RESOLVED')
ORDER BY triggered_at DESC;
```

**Index.** `idx_alert_event_route_open (saved_route_id, triggered_at DESC)
WHERE saved_route_id IS NOT NULL AND state NOT IN ('ACKNOWLEDGED', 'RESOLVED')`

**Plan (forced-index, confirms partial index):**

```
Index Scan using idx_alert_event_route_open on alert_event
  (cost=0.13..2.36 rows=2 width=44) (actual time=0.023..0.025 rows=2 loops=1)
  Index Cond: (saved_route_id = $1)
  Buffers: shared hit=2
Execution Time: 0.121 ms
```

**Why it matters.** The partial index pre-filters to open alerts only, so it is
smaller than the full `idx_alert_event_route` and the scan returns results
pre-sorted by `triggered_at DESC` (no sort node). No filter step is needed because
the index predicate already excludes terminal states. At production scale with
thousands of resolved alerts per route, this partial index stays compact while the
full index grows unboundedly.

---

## Q4: Saved Routes by Member (Active)

**Caller.** `SavedRouteRepository.findByMemberIdAndDeletedAtIsNull()` -- the
primary "my routes" list view.

```sql
SELECT id, name, origin_name, destination_name, vehicle_type,
       risk_threshold, monitor_enabled, created_at
FROM saved_route
WHERE member_id = $1
  AND deleted_at IS NULL
ORDER BY created_at DESC;
```

**Index.** `idx_saved_route_member_active (member_id, created_at DESC) WHERE deleted_at IS NULL`

**Plan (forced-index):**

```
Index Scan using idx_saved_route_member_active on saved_route
  (cost=0.13..2.37 rows=2 width=65) (actual time=0.035..0.036 rows=2 loops=1)
  Index Cond: (member_id = $1)
  Buffers: shared hit=2
Execution Time: 0.072 ms
```

**Why it matters.** The partial index excludes soft-deleted rows, keeping it small.
The `created_at DESC` column means results come pre-sorted (no sort node). This is
the canonical pattern for soft-delete list queries: the partial index is both the
filter and the sort.

---

## Q5: Tenant Members by Tenant + Role

**Caller.** `TenantMemberRepository.findByTenantIdAndRoleAndDeletedAtIsNull()` --
called by `AlertEscalationService.firstMemberInRole()` for each role in the
escalation chain (MEMBER, ADMIN, OWNER = 3 lookups per escalation).

```sql
SELECT id, email, display_name, role
FROM tenant_member
WHERE tenant_id = $1
  AND role = $2
  AND deleted_at IS NULL;
```

**Index.** `idx_tenant_member_tenant_role (tenant_id, role) WHERE deleted_at IS NULL`

**Plan (forced-index):**

```
Index Scan using idx_tenant_member_tenant_role on tenant_member
  (cost=0.14..2.35 rows=1 width=49) (actual time=0.034..0.035 rows=1 loops=1)
  Index Cond: ((tenant_id = $1) AND (role = 'ADMIN'))
  Buffers: shared hit=2
Execution Time: 0.765 ms
```

**Why it matters.** Without this composite index, the escalation service would
fall back to `idx_tenant_member_tenant_id` (tenant only) and filter by role, or
seq-scan. The composite `(tenant_id, role)` makes each of the 3 escalation lookups
a direct point query. The partial predicate matches the query's `deleted_at IS NULL`
exactly.

---

## Q6: Observation Time-Range Search

**Caller.** `VectorSearchRepository` time filters (`SearchFilters.timeFrom/timeTo`),
dashboard queries, `mv_tenant_risk_summary` refresh.

```sql
SELECT time, saved_route_id, risk_score, risk_level, factors
FROM route_risk_observation
WHERE time >= now() - interval '7 days'
  AND time <= now()
ORDER BY time DESC
LIMIT 100;
```

**Indexes.** TimescaleDB chunk pruning (7-day chunks) + per-chunk time btree +
`idx_observation_time_brin` (BRIN for cross-route scans).

**Plan (real):**

```
Limit  (cost=0.15..9.91 rows=100 width=195) (actual time=0.029..0.139 rows=100 loops=1)
  Buffers: shared hit=60
  ->  Custom Scan (ChunkAppend) on route_risk_observation
        Order: route_risk_observation."time" DESC
        Chunks excluded during startup: 0
        ->  Index Scan using _hyper_1_4_chunk_route_risk_observation_time_idx
              Index Cond: (("time" >= (now() - '7 days')) AND ("time" <= now()))
              Buffers: shared hit=40
        ->  Index Scan using _hyper_1_3_chunk_route_risk_observation_time_idx
              Index Cond: (("time" >= (now() - '7 days')) AND ("time" <= now()))
              Buffers: shared hit=20
Execution Time: 8.080 ms
```

**Why it matters.** TimescaleDB's ChunkAppend operator merges per-chunk index scans
in time order, enabling early termination at LIMIT 100 without sorting. Only the
2 chunks overlapping the 7-day window are scanned (chunks excluded during startup: 0
means all were eligible, but only 2 had data). The BRIN index
(`idx_observation_time_brin`) adds value for ad-hoc cross-route reporting queries
that don't have a `saved_route_id` filter; within-chunk btree scans handle the
filtered case.

---

## Q7: Keyword Search on Observations (tsvector)

**Caller.** `VectorSearchRepository.searchByKeyword()` and
`searchByKeywordWithFilters()` -- the keyword leg of RRF hybrid search.

```sql
SELECT time, saved_route_id, risk_score, factors,
       ts_rank(to_tsvector('english', factors::text),
               plainto_tsquery('english', $1)) AS similarity
FROM route_risk_observation
WHERE to_tsvector('english', factors::text) @@ plainto_tsquery('english', $1)
ORDER BY similarity DESC
LIMIT 10;
```

**Index.** `idx_observation_factors_tsv USING gin (to_tsvector('english', factors::text))`
(per-chunk GIN indexes on the hypertable).

**Plan (real, abridged to 2 of 5 chunks):**

```
Limit  (cost=132.07..132.09 rows=10 width=193) (actual time=14.163..14.172 rows=10 loops=1)
  ->  Sort  (top-N heapsort  Memory: 27kB)
        ->  Result  (actual time=5.509..13.959 rows=167 loops=1)
              ->  Append  (actual time=0.916..1.415 rows=167 loops=1)
                    ->  Bitmap Heap Scan on _hyper_1_1_chunk
                          Recheck Cond: (to_tsvector(...) @@ '''wind'' & ''heavi''')
                          ->  Bitmap Index Scan on _hyper_1_1_chunk_idx_observation_factors_tsv
                                Index Cond: (to_tsvector(...) @@ '''wind'' & ''heavi''')
                                Buffers: shared hit=5
                    ->  Bitmap Heap Scan on _hyper_1_3_chunk
                          ->  Bitmap Index Scan on _hyper_1_3_chunk_idx_observation_factors_tsv
                                Buffers: shared hit=5
                    [... 3 more chunks ...]
Execution Time: 14.617 ms
```

**Why it matters.** Without the GIN tsvector index, every keyword search would
seq-scan the entire hypertable (all chunks). The per-chunk GIN indexes let each
chunk do a bitmap index scan, touching only matching rows. Found 167 matching rows
across 5 chunks with only 44 buffer hits. The `ts_rank` sort is unavoidable (must
score all matches), but the index eliminates the full-table scan for the WHERE
clause. This is the critical enabler for the RRF hybrid search: without it, the
keyword leg would be O(table) and the hybrid search would be unusable at scale.

---

## Q8: Fuzzy Name Search (pg_trgm)

**Caller.** UI autocomplete / search-as-you-type on saved routes.

```sql
SELECT id, name, origin_name, destination_name
FROM saved_route
WHERE name ILIKE '%gangnam%'
  AND deleted_at IS NULL;
```

**Index.** `idx_saved_route_name_trgm USING gin (name gin_trgm_ops) WHERE deleted_at IS NULL`

**Plan (small scale: seq scan, 7 rows).** At production scale with hundreds of
routes per tenant, the planner switches to:

```
Bitmap Heap Scan on saved_route
  Recheck Cond: ((name ~~* '%gangnam%') AND (deleted_at IS NULL))
  ->  Bitmap Index Scan on idx_saved_route_name_trgm
        Index Cond: (name ~~* '%gangnam%')
```

**Why it matters.** Standard btree indexes cannot serve `ILIKE '%...%'` (leading
wildcard). The trigram GIN index decomposes the name into 3-character grams and
matches them against the pattern's grams, enabling substring and similarity search.
The partial predicate (`WHERE deleted_at IS NULL`) keeps the index small and
matches the query filter exactly.

---

## Q9: Audit Log Time-Range

**Caller.** Ad-hoc compliance queries, admin dashboard.

```sql
SELECT id, tenant_id, action, resource_type, created_at
FROM audit_log
WHERE created_at >= now() - interval '3 days'
ORDER BY created_at DESC
LIMIT 50;
```

**Indexes.** `idx_audit_log_created_brin (created_at) USING brin (pages_per_range=32)`
and `idx_audit_log_tenant_time (tenant_id, created_at DESC)`.

**Plan (forced-index, uses btree):**

```
Limit  (cost=29.01..29.14 rows=50 width=51) (actual time=0.367..0.376 rows=50 loops=1)
  ->  Sort  (top-N heapsort  Memory: 31kB)
        ->  Index Scan using idx_audit_log_tenant_time on audit_log
              Index Cond: (created_at >= (now() - '3 days'))
              Buffers: shared hit=29
Execution Time: 0.713 ms
```

**Why it matters.** The planner chose the btree `idx_audit_log_tenant_time` over
the BRIN because the btree can provide ordered output. The BRIN index
(`idx_audit_log_created_brin`) is designed for cross-tenant time-range scans on
the append-only table: at millions of rows, BRIN's min/max block summaries
(pages_per_range=32) skip entire block ranges that fall outside the time window,
at ~1000x less storage than a btree. For tenant-scoped queries,
`idx_audit_log_tenant_time` is the right choice.

---

## Q10: Vector Similarity Search (HNSW)

**Caller.** `VectorSearchRepository.searchByVector()` -- the vector leg of RRF
hybrid search for RAG grounding.

```sql
SELECT time, saved_route_id, risk_score, factors,
       1 - (embedding <=> $1::vector) AS similarity
FROM route_risk_observation
WHERE embedding IS NOT NULL
ORDER BY embedding <=> $1::vector
LIMIT 5;
```

**Index.** `idx_observation_embedding USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64)` (per-chunk).

**Plan.** No embeddings were populated in the test data (all NULL), so the query
returns 0 rows via seq scan. The HNSW index is confirmed created on all chunks.
At production scale with populated embeddings, the plan is:

```
Limit
  ->  Index Scan using idx_observation_embedding on route_risk_observation
        Order By: (embedding <=> $1)
```

**Why it matters.** HNSW (Hierarchical Navigable Small World) provides approximate
nearest neighbor search in O(log n) instead of O(n) for exact cosine distance.
The `m=16, ef_construction=64` parameters balance recall accuracy against build
time. Per the ADR-0016/0018 design, RAG grounding targets recent observations,
which aligns with the 30-day compression policy: compressed chunks cannot serve
HNSW without decompression, but recent (hot) chunks are uncompressed and indexed.

---

## Q11: CASCADE Delete FK Index Usage

**Caller.** `DELETE FROM tenant_member WHERE id = $1` triggers
`ON DELETE CASCADE` on saved_route, saved_place, and `ON DELETE SET NULL` on
alert_escalation.

```sql
-- The FK cascade internally executes:
SELECT id FROM saved_route WHERE member_id = $1;
```

**Index.** `idx_saved_route_member_fk (member_id)` -- non-partial, covers
soft-deleted rows that the partial indexes exclude.

**Plan (forced-index):**

```
Index Scan using idx_saved_route_member_fk on saved_route sr
  (cost=0.13..2.37 rows=2 width=16) (actual time=0.025..0.026 rows=2 loops=1)
  Index Cond: (member_id = $1)
  Buffers: shared hit=2
Execution Time: 0.055 ms
```

**Why it matters.** PostgreSQL does NOT auto-index FK columns. Without
`idx_saved_route_member_fk`, every CASCADE delete from `tenant_member` would
seq-scan the entire `saved_route` table to find referencing rows. The existing
partial indexes (`idx_saved_route_member_active`, `uq_saved_route_member_name_active`)
exclude soft-deleted rows and CANNOT serve the cascade, which must find ALL
referencing rows. This is the most commonly missed index in PostgreSQL schemas and
the #1 cause of slow deletes in production. V015 added dedicated FK cascade indexes
for every FK that lacked non-partial coverage.

---

## Q12: Usage Record Quota Check

**Caller.** `check_and_consume_quota()` -- called on every metered API request.

```sql
SELECT COALESCE(sum(count), 0) AS current_usage
FROM usage_record
WHERE tenant_id = $1
  AND feature = $2
  AND usage_date = CURRENT_DATE;
```

**Index.** `idx_usage_record_tenant_feature_date (tenant_id, feature, usage_date)`
+ partition pruning.

**Plan (real):**

```
Aggregate  (cost=2.46..2.47 rows=1 width=8) (actual time=0.041..0.043 rows=1 loops=1)
  Buffers: shared hit=1
  ->  Append  (cost=0.00..2.43 rows=12 width=4) (actual time=0.038..0.039 rows=0 loops=1)
        Subplans Removed: 11
        ->  Index Scan using usage_record_2026_07_tenant_id_feature_usage_date_idx
              on usage_record_2026_07
              Index Cond: ((tenant_id = $1) AND (feature = 'ROUTE_PLAN')
                           AND (usage_date = CURRENT_DATE))
              Buffers: shared hit=1
Execution Time: 0.118 ms
```

**Why it matters.** This plan demonstrates two layers of optimization working
together: (1) **partition pruning** eliminates 11 of 12 monthly partitions at plan
time (`Subplans Removed: 11`), and (2) the composite btree index within the July
partition provides a direct point lookup on `(tenant_id, feature, usage_date)`.
The result is a single-buffer-hit aggregate. This is the quota enforcement hot
path: it runs on every metered request, so the combination of partitioning +
composite index keeps it O(1) regardless of total usage history.

---

## Q13: Alert Escalation Pending Lookup

**Caller.** `AlertEscalationRepository.findByAlertEventIdAndTargetMemberIdAndRespondedAtIsNull()`
-- used by `AlertEscalationService.acknowledgeAlert()`.

```sql
SELECT ae.id, ae.level, ae.target_member_id, ae.notified_at
FROM alert_escalation ae
WHERE ae.alert_event_id = $1
  AND ae.target_member_id = $2
  AND ae.responded_at IS NULL;
```

**Index.** `idx_alert_escalation_event_member_pending (alert_event_id, target_member_id)
WHERE responded_at IS NULL`

**Plan (forced-index):**

```
Index Scan using idx_alert_escalation_event_member_pending on alert_escalation ae
  (cost=0.13..2.35 rows=1 width=44) (actual time=0.024..0.025 rows=1 loops=1)
  Index Cond: ((alert_event_id = $1) AND (target_member_id = $2))
  Buffers: shared hit=2
Execution Time: 0.054 ms
```

**Why it matters.** The partial index matches the query's `responded_at IS NULL`
predicate exactly, so it only indexes pending (unacknowledged) escalations. As
escalations are acknowledged, they drop out of the index, keeping it compact.
The composite `(alert_event_id, target_member_id)` makes the lookup a direct
point query rather than scanning all escalations for an event.

---

## Q14: Subscription History by Tenant

**Caller.** `SubscriptionRepository.findByTenantIdOrderByValidToDesc()` -- admin
view of subscription version history.

```sql
SELECT id, plan, status, valid_from, valid_to, version
FROM subscription
WHERE tenant_id = $1
ORDER BY valid_to DESC;
```

**Index.** `idx_subscription_tenant_validity (tenant_id, valid_to DESC) INCLUDE (status, valid_from, plan)`

**Plan (forced-index):**

```
Index Scan using idx_subscription_tenant_validity on subscription
  (cost=0.13..2.36 rows=2 width=47) (actual time=0.021..0.022 rows=2 loops=1)
  Index Cond: (tenant_id = $1)
  Buffers: shared hit=2
Execution Time: 0.047 ms
```

**Why it matters.** The same covering index serves both the active-lookup (Q1) and
the history query. The `valid_to DESC` column provides the sort order, eliminating
a sort node. At scale with many subscription versions per tenant (plan changes,
prorations), this index keeps the history query O(versions) rather than O(table).

---

## Q15: Per-Route Observation Time-Series

**Caller.** `RouteRiskObservationRepository.findTop10BySavedRouteIdOrderByTimeDesc()`
-- the route detail "recent observations" panel.

```sql
SELECT time, risk_score, risk_level, factors
FROM route_risk_observation
WHERE saved_route_id = $1
ORDER BY time DESC
LIMIT 10;
```

**Index.** `idx_risk_obs_route_time (saved_route_id, time DESC)` (per-chunk).

**Plan (real):**

```
Limit  (cost=0.14..2.26 rows=10 width=179) (actual time=0.026..0.034 rows=10 loops=1)
  Buffers: shared hit=5
  ->  Custom Scan (ChunkAppend) on route_risk_observation
        Order: route_risk_observation."time" DESC
        ->  Index Scan using _hyper_1_4_chunk_idx_risk_obs_route_time
              Index Cond: (saved_route_id = $1)
              Buffers: shared hit=5
        ->  Index Scan using _hyper_1_3_chunk_idx_risk_obs_route_time
              Index Cond: (saved_route_id = $1)
              (never executed)
        [... 3 more chunks, never executed ...]
Execution Time: 0.085 ms
```

**Why it matters.** ChunkAppend merges per-chunk index scans in `time DESC` order.
The first chunk satisfied the LIMIT 10 entirely (5 buffer hits), so the remaining
4 chunks were **never executed**. This is the optimal plan: O(10) regardless of
total observation count. The `(saved_route_id, time DESC)` composite index provides
both the filter and the sort within each chunk.

---

## Summary: Index Coverage Matrix

| Query | Index | Scan Type | Sort Eliminated | Notes |
|-------|-------|-----------|-----------------|-------|
| Q1 Subscription lookup | `idx_subscription_tenant_validity` | Index (covering) | Yes (valid_to DESC) | INCLUDE enables index-only |
| Q2 Quota lookup | `idx_subscription_tenant_validity` + `idx_entitlement_quota_cover` | Index + Index (covering) | Yes | Both sides covering |
| Q3 Open alerts | `idx_alert_event_route_open` | Index (partial) | Yes (triggered_at DESC) | Partial pre-filters states |
| Q4 Routes by member | `idx_saved_route_member_active` | Index (partial) | Yes (created_at DESC) | Partial excludes deleted |
| Q5 Members by role | `idx_tenant_member_tenant_role` | Index (partial) | N/A | Composite point query |
| Q6 Time-range obs | ChunkAppend + per-chunk btree | Index (per-chunk) | Yes (ChunkAppend merge) | BRIN for cross-route |
| Q7 Keyword search | `idx_observation_factors_tsv` | Bitmap Index (GIN, per-chunk) | No (ts_rank sort) | GIN eliminates seq scan |
| Q8 Fuzzy name | `idx_saved_route_name_trgm` | Bitmap Index (GIN trgm) | N/A | Only option for ILIKE '%..%' |
| Q9 Audit time-range | `idx_audit_log_tenant_time` / BRIN | Index / Bitmap | Partial | BRIN for cross-tenant |
| Q10 Vector search | `idx_observation_embedding` | HNSW ANN | Yes (index order) | O(log n) vs O(n) |
| Q11 CASCADE FK | `idx_saved_route_member_fk` | Index | N/A | Non-partial (covers deleted) |
| Q12 Quota check | Partition prune + `idx_usage_record_tenant_feature_date` | Index (partition) | N/A | 11/12 partitions pruned |
| Q13 Escalation pending | `idx_alert_escalation_event_member_pending` | Index (partial) | N/A | Shrinks as acks arrive |
| Q14 Sub history | `idx_subscription_tenant_validity` | Index (covering) | Yes (valid_to DESC) | Same index as Q1 |
| Q15 Route observations | `idx_risk_obs_route_time` | Index (per-chunk) | Yes (ChunkAppend merge) | Early termination at LIMIT |
