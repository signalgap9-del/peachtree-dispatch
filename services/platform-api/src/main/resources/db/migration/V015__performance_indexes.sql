-- V015: Query-driven performance indexes
--
-- Every index below is traced to a concrete query pattern found in the
-- repository layer (Spring Data derived queries, @Query JPQL/native SQL),
-- JdbcTemplate calls in VectorSearchRepository / EmbeddingService, or
-- PL/pgSQL stored functions (check_and_consume_quota, has_permission,
-- transition_alert_state, prorate_subscription).
--
-- NOTE: Flyway runs migrations inside a transaction, so CREATE INDEX
-- CONCURRENTLY is not available here. All indexes use plain CREATE INDEX.
-- On large production tables, consider running these via a separate
-- ops script with CONCURRENTLY outside a transaction.

----------------------------------------------------------------------
-- 0. Extensions
----------------------------------------------------------------------

CREATE EXTENSION IF NOT EXISTS pg_trgm;

----------------------------------------------------------------------
-- 1. Foreign-key indexes
--    PostgreSQL does NOT auto-index FK columns. Every FK that lacks a
--    usable index forces a sequential scan on the child table during
--    CASCADE deletes or SET NULL updates. Partial indexes that exclude
--    soft-deleted rows do NOT help here: the FK cascade must find ALL
--    referencing rows, including soft-deleted ones.
----------------------------------------------------------------------

-- saved_route.member_id -> tenant_member(id) ON DELETE CASCADE
-- Existing partial indexes (idx_saved_route_member_active, uq_saved_route_member_name_active)
-- exclude soft-deleted rows and cannot serve CASCADE deletes.
CREATE INDEX IF NOT EXISTS idx_saved_route_member_fk
    ON saved_route (member_id);

COMMENT ON INDEX idx_saved_route_member_fk IS
    'FK cascade index: lets ON DELETE CASCADE from tenant_member find all '
    'saved_route rows (including soft-deleted) without a seq scan. '
    'Existing partial indexes exclude deleted_at IS NOT NULL rows.';

-- saved_route.workspace_id -> workspace(id) ON DELETE SET NULL
-- idx_saved_route_workspace is partial (WHERE deleted_at IS NULL), so
-- SET NULL cascades on soft-deleted rows would seq-scan.
CREATE INDEX IF NOT EXISTS idx_saved_route_workspace_fk
    ON saved_route (workspace_id)
    WHERE workspace_id IS NOT NULL;

COMMENT ON INDEX idx_saved_route_workspace_fk IS
    'FK cascade index: lets ON DELETE SET NULL from workspace find all '
    'saved_route rows referencing the deleted workspace, including '
    'soft-deleted rows that idx_saved_route_workspace excludes.';

-- saved_place.member_id -> tenant_member(id) ON DELETE CASCADE
CREATE INDEX IF NOT EXISTS idx_saved_place_member_fk
    ON saved_place (member_id);

COMMENT ON INDEX idx_saved_place_member_fk IS
    'FK cascade index: lets ON DELETE CASCADE from tenant_member find all '
    'saved_place rows (including soft-deleted) without a seq scan.';

-- saved_place.workspace_id -> workspace(id) ON DELETE SET NULL
CREATE INDEX IF NOT EXISTS idx_saved_place_workspace_fk
    ON saved_place (workspace_id)
    WHERE workspace_id IS NOT NULL;

COMMENT ON INDEX idx_saved_place_workspace_fk IS
    'FK cascade index: lets ON DELETE SET NULL from workspace find all '
    'saved_place rows referencing the deleted workspace, including '
    'soft-deleted rows that idx_saved_place_workspace excludes.';

-- alert_escalation.target_member_id -> tenant_member(id) ON DELETE SET NULL
-- idx_alert_escalation_pending is partial (WHERE responded_at IS NULL),
-- so SET NULL cascades on already-responded rows would seq-scan.
CREATE INDEX IF NOT EXISTS idx_alert_escalation_target_fk
    ON alert_escalation (target_member_id)
    WHERE target_member_id IS NOT NULL;

COMMENT ON INDEX idx_alert_escalation_target_fk IS
    'FK cascade index: lets ON DELETE SET NULL from tenant_member find all '
    'alert_escalation rows targeting the deleted member, including '
    'already-responded rows that idx_alert_escalation_pending excludes.';

-- tenant_member.tenant_id -> tenant(id) ON DELETE CASCADE
-- idx_tenant_member_tenant_id is partial (WHERE deleted_at IS NULL),
-- so CASCADE deletes of soft-deleted members would seq-scan.
CREATE INDEX IF NOT EXISTS idx_tenant_member_tenant_fk
    ON tenant_member (tenant_id);

COMMENT ON INDEX idx_tenant_member_tenant_fk IS
    'FK cascade index: lets ON DELETE CASCADE from tenant find all '
    'tenant_member rows (including soft-deleted) without a seq scan. '
    'idx_tenant_member_tenant_id only covers active members.';

----------------------------------------------------------------------
-- 2. Composite indexes for hot query patterns
----------------------------------------------------------------------

-- SubscriptionRepository.findActiveByTenantId():
--   WHERE tenant_id = ? AND valid_from <= ? AND valid_to > ?
--   ORDER BY valid_to DESC
-- SubscriptionRepository.findByTenantIdOrderByValidToDesc():
--   WHERE tenant_id = ? ORDER BY valid_to DESC
-- EntitlementRepository.findActiveQuotaLimit() (native):
--   JOIN subscription s ... WHERE s.tenant_id = ? AND s.status IN (...)
--   AND s.valid_from <= now() AND s.valid_to > now() ORDER BY s.valid_to DESC
-- check_and_consume_quota() function:
--   WHERE s.tenant_id = ? AND s.valid_to = 'infinity' AND s.status IN (...)
--
-- INCLUDE columns enable index-only scans: the planner can satisfy the
-- status/valid_from filters and return plan without heap fetches.
CREATE INDEX IF NOT EXISTS idx_subscription_tenant_validity
    ON subscription (tenant_id, valid_to DESC)
    INCLUDE (status, valid_from, plan);

COMMENT ON INDEX idx_subscription_tenant_validity IS
    'Covering index for temporal subscription lookups: '
    'SubscriptionRepository.findActiveByTenantId(), '
    'findByTenantIdOrderByValidToDesc(), '
    'EntitlementRepository.findActiveQuotaLimit(), and '
    'check_and_consume_quota(). INCLUDE columns enable index-only scans.';

-- TenantMemberRepository.findByTenantIdAndRoleAndDeletedAtIsNull():
--   WHERE tenant_id = ? AND role = ? AND deleted_at IS NULL
-- Called by AlertEscalationService.firstMemberInRole() during every
-- alert escalation fan-out (3 role lookups per escalation).
CREATE INDEX IF NOT EXISTS idx_tenant_member_tenant_role
    ON tenant_member (tenant_id, role)
    WHERE deleted_at IS NULL;

COMMENT ON INDEX idx_tenant_member_tenant_role IS
    'Serves TenantMemberRepository.findByTenantIdAndRoleAndDeletedAtIsNull(), '
    'called by AlertEscalationService.firstMemberInRole() for each role in '
    'the escalation chain (MEMBER, ADMIN, OWNER).';

-- AlertEventRepository.findOpenByRouteId():
--   WHERE saved_route_id = ? AND state NOT IN ('ACKNOWLEDGED','RESOLVED')
--   ORDER BY triggered_at DESC
-- idx_alert_event_route covers (saved_route_id, triggered_at DESC) but
-- includes acknowledged/resolved rows, forcing a filter step. This
-- partial index is smaller and pre-filters to open alerts only.
CREATE INDEX IF NOT EXISTS idx_alert_event_route_open
    ON alert_event (saved_route_id, triggered_at DESC)
    WHERE saved_route_id IS NOT NULL
      AND state NOT IN ('ACKNOWLEDGED', 'RESOLVED');

COMMENT ON INDEX idx_alert_event_route_open IS
    'Serves AlertEventRepository.findOpenByRouteId(): open alerts per route '
    'ordered by trigger time. Partial index excludes terminal states, '
    'making it smaller and faster than idx_alert_event_route for this query.';

-- AlertEscalationRepository.findByAlertEventIdAndTargetMemberIdAndRespondedAtIsNull():
--   WHERE alert_event_id = ? AND target_member_id = ? AND responded_at IS NULL
-- Called by AlertEscalationService.acknowledgeAlert() to mark pending
-- escalations as responded. Neither existing index covers this composite.
CREATE INDEX IF NOT EXISTS idx_alert_escalation_event_member_pending
    ON alert_escalation (alert_event_id, target_member_id)
    WHERE responded_at IS NULL;

COMMENT ON INDEX idx_alert_escalation_event_member_pending IS
    'Serves AlertEscalationRepository.'
    'findByAlertEventIdAndTargetMemberIdAndRespondedAtIsNull(), used by '
    'AlertEscalationService.acknowledgeAlert() to find pending escalations '
    'for a specific event+member pair.';

----------------------------------------------------------------------
-- 3. Partial indexes for embedding backfill
--    EmbeddingService.reindexObservations() and reindexAlerts() scan
--    for rows WHERE embedding IS NULL. Without a partial index, these
--    seq-scan the entire table on every backfill batch.
----------------------------------------------------------------------

CREATE INDEX IF NOT EXISTS idx_observation_unembedded
    ON route_risk_observation (saved_route_id, time)
    WHERE embedding IS NULL;

COMMENT ON INDEX idx_observation_unembedded IS
    'Serves EmbeddingService.reindexObservations(): '
    'SELECT ... FROM route_risk_observation WHERE embedding IS NULL LIMIT ?. '
    'Partial index keeps the backfill scan O(batch) instead of O(table).';

CREATE INDEX IF NOT EXISTS idx_alert_unembedded
    ON alert_event (triggered_at)
    WHERE embedding IS NULL;

COMMENT ON INDEX idx_alert_unembedded IS
    'Serves EmbeddingService.reindexAlerts(): '
    'SELECT ... FROM alert_event WHERE embedding IS NULL LIMIT ?. '
    'Partial index keeps the backfill scan O(batch) instead of O(table).';

----------------------------------------------------------------------
-- 4. BRIN indexes for append-only / time-ordered tables
--    BRIN stores min/max summaries per block range, making them ~1000x
--    smaller than btree for physically time-ordered data. They excel at
--    range scans (WHERE time BETWEEN ...) on large append-only tables.
--
--    For partitioned tables (usage_record) and hypertables
--    (route_risk_observation), partition/chunk pruning already narrows
--    the scan. BRIN adds value for within-partition time-range scans
--    and ad-hoc reporting queries that span many tenants.
----------------------------------------------------------------------

CREATE INDEX IF NOT EXISTS idx_audit_log_created_brin
    ON audit_log USING brin (created_at)
    WITH (pages_per_range = 32);

COMMENT ON INDEX idx_audit_log_created_brin IS
    'BRIN for ad-hoc time-range scans on audit_log (append-only, physically '
    'ordered by BIGSERIAL insert). Complements the btree indexes that serve '
    'specific tenant/resource queries. pages_per_range=32 for tighter '
    'summaries on a high-insert table.';

CREATE INDEX IF NOT EXISTS idx_usage_record_date_brin
    ON usage_record USING brin (usage_date);

COMMENT ON INDEX idx_usage_record_date_brin IS
    'BRIN for cross-tenant time-range reporting on usage_record. Partition '
    'pruning handles month-level filtering; BRIN helps within-partition '
    'date-range scans (e.g., dashboard queries for a date window).';

CREATE INDEX IF NOT EXISTS idx_observation_time_brin
    ON route_risk_observation USING brin (time);

COMMENT ON INDEX idx_observation_time_brin IS
    'BRIN for cross-route time-range scans on route_risk_observation. '
    'TimescaleDB chunk pruning handles 7-day-level filtering; BRIN helps '
    'within-chunk time-range scans for VectorSearchRepository time filters '
    '(SearchFilters.timeFrom/timeTo) and mv_tenant_risk_summary refreshes.';

----------------------------------------------------------------------
-- 5. Covering index for entitlement quota checks
--    EntitlementRepository.findActiveQuotaLimit() joins entitlement to
--    subscription and returns quota_limit. The existing unique index
--    uq_entitlement_sub_feature (subscription_id, feature) covers the
--    join but requires a heap fetch for quota_limit. INCLUDE turns this
--    into an index-only scan.
----------------------------------------------------------------------

CREATE INDEX IF NOT EXISTS idx_entitlement_quota_cover
    ON entitlement (subscription_id, feature)
    INCLUDE (quota_limit, saved_route_limit, saved_place_limit);

COMMENT ON INDEX idx_entitlement_quota_cover IS
    'Covering index for EntitlementRepository.findActiveQuotaLimit() and '
    'check_and_consume_quota(): enables index-only scan returning quota_limit '
    'without heap fetch. Also covers saved_route_limit/saved_place_limit '
    'checks used by the saved-data quota enforcement path.';

----------------------------------------------------------------------
-- 6. Text search indexes
----------------------------------------------------------------------

-- pg_trgm GIN indexes for fuzzy/prefix search on user-facing names.
-- These serve UI autocomplete and search-as-you-type on saved routes
-- and places (e.g., name ILIKE '%gangnam%' or similarity(name, '...')).
-- Partial: only active (non-deleted) rows are searchable.

CREATE INDEX IF NOT EXISTS idx_saved_route_name_trgm
    ON saved_route USING gin (name gin_trgm_ops)
    WHERE deleted_at IS NULL;

COMMENT ON INDEX idx_saved_route_name_trgm IS
    'Trigram GIN for fuzzy/prefix search on saved_route.name '
    '(ILIKE, similarity). Serves UI autocomplete for route search. '
    'Partial: only active routes.';

CREATE INDEX IF NOT EXISTS idx_saved_place_name_trgm
    ON saved_place USING gin (name gin_trgm_ops)
    WHERE deleted_at IS NULL;

COMMENT ON INDEX idx_saved_place_name_trgm IS
    'Trigram GIN for fuzzy/prefix search on saved_place.name '
    '(ILIKE, similarity). Serves UI autocomplete for place search. '
    'Partial: only active places.';

-- GIN tsvector index for keyword search on risk observation factors.
-- VectorSearchRepository.searchByKeyword() and searchByKeywordWithFilters()
-- execute:
--   WHERE to_tsvector('english', factors::text) @@ plainto_tsquery('english', ?)
-- Without this index, every keyword search seq-scans the hypertable.
-- On a hypertable, this creates per-chunk GIN indexes automatically.

CREATE INDEX IF NOT EXISTS idx_observation_factors_tsv
    ON route_risk_observation
    USING gin (to_tsvector('english', factors::text));

COMMENT ON INDEX idx_observation_factors_tsv IS
    'GIN tsvector index for VectorSearchRepository.searchByKeyword() and '
    'searchByKeywordWithFilters(): full-text search over the JSONB factors '
    'column. Enables the keyword leg of the RRF hybrid search. '
    'Per-chunk GIN indexes on the TimescaleDB hypertable.';

----------------------------------------------------------------------
-- 7. Indexes already present (NOT duplicated above)
--    Listed here for reviewer reference:
--
--    FK coverage already adequate:
--      subscription.tenant_id     -> idx_subscription_tenant_status (tenant_id, status)
--      alert_event.tenant_id      -> idx_alert_event_tenant_state (tenant_id, state)
--      alert_event.saved_route_id -> idx_alert_event_route (saved_route_id, triggered_at DESC)
--      alert_escalation.alert_event_id -> idx_alert_escalation_event (alert_event_id, level)
--      workspace.tenant_id        -> idx_workspace_tenant_id (tenant_id)
--      workspace_member.workspace_id -> UNIQUE (workspace_id, member_id)
--      workspace_member.member_id -> idx_workspace_member_member_id (member_id)
--      entitlement.subscription_id -> idx_entitlement_subscription_id (subscription_id)
--      api_key.tenant_id          -> idx_api_key_tenant (tenant_id)
--      api_key.member_id          -> idx_api_key_member (member_id) WHERE NOT NULL
--      idempotency_key            -> PK (tenant_id, operation, key_hash)
--
--    Query coverage already adequate:
--      SavedRouteRepository.findByMemberIdAndDeletedAtIsNull
--        -> idx_saved_route_member_active (member_id, created_at DESC) WHERE deleted_at IS NULL
--      SavedRouteRepository.findByWorkspaceIdAndDeletedAtIsNull
--        -> idx_saved_route_workspace (workspace_id) WHERE deleted_at IS NULL
--      SavedRouteRepository.findByMonitorEnabledTrueAndDeletedAtIsNull
--        -> idx_saved_route_monitor (member_id) WHERE deleted_at IS NULL AND monitor_enabled = true
--        (partial index predicate matches query WHERE clause; planner uses full index scan)
--      SavedPlaceRepository.findByMemberIdAndDeletedAtIsNull
--        -> idx_saved_place_member_active
--      SavedPlaceRepository.findByWorkspaceIdAndDeletedAtIsNull
--        -> idx_saved_place_workspace
--      AlertEventRepository.countByTenantIdAndStateAndTriggeredAtAfter
--        -> idx_alert_tenant_state (tenant_id, state, triggered_at DESC)
--      UsageRecordRepository (all queries)
--        -> idx_usage_record_tenant_feature_date + PK
--      AuditLogRepository.findTop50ByTenantIdOrderByCreatedAtDesc
--        -> idx_audit_log_tenant_time (tenant_id, created_at DESC)
--      RouteRiskObservationRepository.findTop10BySavedRouteIdOrderByTimeDesc
--        -> idx_risk_obs_route_time (saved_route_id, time DESC)
--      ApiKeyRepository.findByKeyHash -> UNIQUE (key_hash)
--      ApiKeyRepository.findByTenantId -> idx_api_key_tenant
--      IdempotencyKeyRepository.findByTenantIdAndOperationAndKeyHash -> PK
--      WorkspaceRepository.findByTenantId -> idx_workspace_tenant_id
--      WorkspaceMemberRepository.findByMemberIdAndWorkspaceId
--        -> idx_workspace_member_member_id + UNIQUE
--      SubscriptionRepository.findByLemonsqueezySubscriptionId
--        -> uq_subscription_ls_id
--      TenantMemberRepository.findByTenantIdAndEmailAndDeletedAtIsNull
--        -> uq_tenant_member_active_email
----------------------------------------------------------------------
