# ADR 0011: Dual-Ledger Quota Counting

## Status

Accepted

## Context

Every authenticated API call checks a quota counter before proceeding:
route plans, place searches, location risk queries, and alert searches all
consume a daily allowance defined by the tenant's plan (FREE: 10 route
plans/day, PRO: 200, TEAM: 2000). The entitlement check sits on the critical
request path.

The current preview implementation (`EntitlementService.java`) calls
`UsageRepository.incrementAndGet()`, which in the DynamoDB backend performs a
conditional `UPDATE` with `ADD` semantics. This works at preview scale but
introduces a database round-trip on every request:

- DynamoDB `UpdateItem` with `ADD`: ~5-15ms per call.
- PostgreSQL `UPDATE ... SET quantity = quantity + 1 RETURNING quantity`:
  ~2-8ms with connection pooling, but contends on the same row under
  concurrency, creating a write hotspot.

At 100+ concurrent users hitting the same tenant's quota row, PostgreSQL row
lock contention on `usage_record` becomes the throughput bottleneck. The quota
check must not serialize all requests for a tenant behind a single row lock.

## Decision

Use **Redis `INCR` as the primary real-time counter** (sub-millisecond) and
**PostgreSQL `usage_record` as the durable ledger**, reconciled every 5
minutes by a scheduled job.

### Write path (hot path)

```
Request → EntitlementService.consume()
  → RedisQuotaCounter.increment(tenantId, feature, date)
    → INCR quota:{tenantId}:{feature}:{date}
    → EXPIRE quota:{tenantId}:{feature}:{date} 172800  (48h TTL)
  → if count > limit → throw QuotaExceededException
  → proceed with business logic
```

Redis `INCR` is atomic and single-threaded per key, so concurrent requests for
the same tenant do not contend on a database row lock. Latency is ~0.1-0.5ms
versus ~5-15ms for a DynamoDB or PostgreSQL round-trip.

### Reconciliation (background)

A scheduled job runs every 5 minutes:

1. `SCAN` Redis for keys matching `quota:*`.
2. For each key, read the current count with `GET`.
3. Upsert into `usage_record` with
   `INSERT ... ON CONFLICT (tenant_id, feature_code, usage_date) DO UPDATE SET quantity = GREATEST(usage_record.quantity, EXCLUDED.quantity)`.
4. Keys that have been reconciled and are older than 24 hours are deleted.

`GREATEST` ensures the database never regresses if a reconciliation runs
concurrently with an increment.

### Startup recovery

On application startup (or Redis restart), the reconciliation job runs once
immediately, seeding Redis counters from the database:

```
SELECT tenant_id, feature_code, usage_date, quantity
FROM usage_record
WHERE usage_date = CURRENT_DATE
```

Each row is loaded into Redis with `SET quota:{...} {quantity} EX 172800 NX`
(`NX` prevents overwriting a counter that is already ahead).

## Alternatives Considered

| Option | Why not |
| --- | --- |
| **Database-only counting** | Row lock contention on the quota row serializes concurrent requests for the same tenant. At 100+ concurrent users, p99 latency degrades to 50-200ms on the quota check alone. |
| **Redis-only (no database)** | Redis persistence (RDB/AOF) does not guarantee zero data loss. A crash loses up to the last fsync interval of increments. For a billing-adjacent counter, this is unacceptable as the sole record. |
| **DynamoDB `ADD` (current)** | Works at preview scale but does not benefit from connection pooling or local transaction atomicity with the business write. Also cannot participate in the PostgreSQL RLS boundary. |
| **PostgreSQL advisory locks** | Reduces row contention but still requires a database round-trip per increment. Does not solve the latency problem; only the concurrency problem. |
| **Kafka-based counting** | Asynchronous by nature; the quota check needs a synchronous answer ("is this request allowed?"). Adding Kafka to the request path adds latency and complexity without benefit. |

## Consequences

- **Up to 5 minutes of usage data at risk on Redis failure.** If Redis crashes
  before the next reconciliation, increments since the last reconciliation are
  lost. The tenant's effective quota resets slightly, allowing a few extra
  requests. For a portfolio preview, this is acceptable. For billing-grade
  metering, the reconciliation interval would drop to 30 seconds and Redis
  would run with AOF `appendfsync everysec`.
- **Quota checks are sub-millisecond.** The hot path no longer touches
  PostgreSQL, removing the row-lock bottleneck entirely.
- **The database remains the source of truth.** Dashboards, admin views, and
  billing reconciliation read from `usage_record`, not Redis. The Redis
  counter is a performance optimization, not a second source of truth.
- **Operational dependency on Redis.** Redis becomes a required service for
  authenticated API traffic. The Docker Compose `production-data` profile
  includes Redis 7. Health checks (`/health`) report Redis connectivity.
- **Code changes.** `RedisQuotaCounter` implements the existing
  `UsageRepository` interface, so `EntitlementService` is unchanged. The
  reconciliation job is a new `@Scheduled` component
  (`UsageReconciliationJob`).
