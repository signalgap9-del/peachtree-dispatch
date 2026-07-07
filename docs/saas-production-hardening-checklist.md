# SaaS Production Hardening Checklist

Date: 2026-07-07

AtmosPath is a weather-risk navigation SaaS preview. This checklist is the
implementation roadmap for raising the project from portfolio MVP to a
production-shaped SaaS system that can survive senior/startup interview review.

## Review Standard

The bar is not "many features." The bar is whether the repo shows production
judgment:

- tenant and user boundaries are explicit;
- protected data is owner-scoped at application and database layers;
- quota, idempotency, and abuse controls are server-side;
- monitoring data has durable history;
- public APIs have stable contracts and error envelopes;
- operations can diagnose failure without exposing secrets;
- deferred work is named precisely and sliced into verifiable increments.

## Current Batch

Implemented in the SaaS hardening foundation slice:

- `TenantRole` enum replaces stringly-typed roles.
- `TenantAuthorizationService` makes saved-asset authorization explicit.
- Saved route/place controllers resolve user scope through `TenantContext`.
- Saved route create/update records durable `RouteRiskObservation` entries.
- `/risk-history` reads persisted route risk observations and only uses a
  labelled legacy fallback for old data with no observations.
- DynamoDB stores observations under the user partition with route-specific sort
  keys.
- PostGIS stores observations in `risk_exposure` behind existing owner RLS.

## Priority Matrix

| Area | Current state | Criticality | Next implementation |
| --- | --- | --- | --- |
| Authentication | Cognito/JWT path exists; public preview remains available | High | Finish deployed Google OAuth smoke test and document token claims |
| Authorization | Owner-scoped saved data plus explicit saved-asset role guard | Critical | Add controller tests for cross-user 404/403 behavior |
| Multi-tenancy | Personal tenant context exists; role enum added | Critical | Add tenant membership table/model before team SaaS claims |
| DB isolation | DynamoDB owner keys and optional PostGIS RLS exist | Critical | Add tenant_id columns when workspace SaaS becomes real |
| Quota/entitlement | Plan limits and daily usage counters exist | High | Add idempotent usage event keys for retries |
| Idempotency | Conditional owner writes exist; event idempotency incomplete | High | Add idempotency key table/items for POST route save and monitor jobs |
| Risk history | Durable route risk observation interface added | Critical | Record monitor refresh observations from real NWS/WZDx/511 joins |
| Road events | WZDx registry discovery exists | High | Fetch selected state feeds and join road events to route edges |
| API contract | DTOs exist in many places; risk proxy still uses flexible JSON | Medium | Add typed public API response contracts around the highest-value routes |
| Observability | Request IDs, status page, CloudWatch/IaC exist | High | Add structured app events for provider failures and quota denials |
| Async jobs | SQS/DLQ infra shape exists; optimization remains sync | Medium | Move large VRP/monitor refresh to job resource with status polling |
| Security | Security headers, origin verify, SSRF guard, RLS path exist | Critical | Add managed WAF/CSP report plan before broad public traffic |
| Billing | Billing intentionally disabled | Low now | Keep as "SaaS-ready entitlement"; do not claim paid SaaS yet |
| Domain cleanup | Weather-risk product direction is clear; old dispatch names remain | Medium | Rename or quarantine legacy delivery/dispatch surfaces |

## Implementation Phases

### Phase 1: SaaS Boundary Foundation

Acceptance criteria:

- `TenantContext` contains tenant, user, plan, subscription status, and typed
  role.
- Protected saved data paths do not derive user IDs ad hoc in controllers.
- Saved routes have durable risk observations.
- Repository tests prove owner-scoped DynamoDB and RLS-backed PostGIS paths.

Status: in progress.

### Phase 2: Idempotency and Durable Monitoring

Acceptance criteria:

- `Idempotency-Key` is accepted on mutation endpoints and deduped server-side.
- Saved-route monitor refresh writes a `RouteRiskObservation`.
- Duplicate refresh/job execution cannot double-count usage or duplicate
  notifications.
- Risk history includes source status, model version, observed time, and expiry.

### Phase 3: Road-Event Edge Joins

Acceptance criteria:

- WZDx/511 road events are normalized into a stable internal event model.
- Route edges can be scored by event distance/intersection, severity, and age.
- API responses distinguish weather, flood, road closure, work zone, crash, and
  winter/black-ice risk.
- Tests include at least one closure/work-zone edge risk scenario.

### Phase 4: Public API Contract Hardening

Acceptance criteria:

- Top-level public responses have typed DTOs and versioned model fields.
- Error responses consistently use `code`, `message`, `requestId`, and optional
  details.
- List endpoints that can grow are paginated.
- Flexible `JsonNode` proxying remains internal only or is wrapped by typed
  boundary DTOs.

### Phase 5: Production Operations

Acceptance criteria:

- Provider timeout/failure/quota/authz events are emitted as structured logs.
- Synthetic monitors cover home, health, map route plan, alert search, and saved
  route APIs.
- Cloud load test strategy remains bounded by reserved concurrency and budget
  alarms.
- Runbooks explain rollback, provider degradation, quota spikes, and auth
  failures.

## Interview Positioning

Say this:

> AtmosPath is a production-shaped weather-risk navigation SaaS preview. The
> implemented boundary includes Cognito-ready auth, tenant context, owner-scoped
> saved data, quota metering, route risk history, serverless AWS IaC, CI/CD, and
> local stress/performance evidence. The next production slices are idempotent
> monitor jobs, deeper WZDx/511 edge joins, typed public contracts, and
> synthetic monitoring.

Do not say this yet:

> It is a fully launched paid SaaS with complete team billing, continuous
> nationwide high-resolution raster processing, and fully trained production ML.
