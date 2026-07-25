# ADR 0010: Production Data Stack

## Status

Accepted

## Context

The portfolio preview runs on DynamoDB single-table design (ADR-0003,
ADR-0007). DynamoDB delivers zero idle cost via on-demand billing and
single-digit-ms reads for owner-scoped queries, which is ideal for a deployed
preview that must not accrue a monthly bill.

The SaaS production path requires capabilities DynamoDB does not provide:

- **Relational joins.** Subscription → entitlement → usage queries span
  multiple entity types. DynamoDB requires denormalization or multiple
  round-trips for what PostgreSQL resolves in one JOIN.
- **Row-Level Security.** Multi-tenant isolation must be enforced at the
  database layer, not only in application code. PostgreSQL RLS provides
  policy-driven tenant scoping that survives application bugs.
- **Time-series queries.** Route risk observations arrive at high frequency
  and need compression, retention policies, and time-range aggregation.
  TimescaleDB provides this natively on PostgreSQL.
- **Transactional consistency.** Idempotency key insertion and the business
  write must commit atomically. DynamoDB TransactWriteItems has a 100-item
  limit and no stored-procedure equivalent.
- **Spatial queries.** PostGIS GiST indexes support corridor-intersection and
  proximity queries that DynamoDB cannot express.

## Decision

Adopt **PostgreSQL 16 + TimescaleDB** as the production OLTP and time-series
store, **Redis 7** for hot-path caching, quota counters, and distributed
locks, and **Kafka + Debezium** for change data capture (CDC).

### PostgreSQL 16 + TimescaleDB

- Single engine for relational OLTP, spatial (PostGIS), and time-series
  (hypertables, compression, retention).
- RLS policies enforce tenant isolation at the database layer.
- Stored functions (`transition_alert_state()`, `change_subscription_plan()`)
  move critical state machines into the database where they cannot be bypassed
  by application bugs.
- Flyway manages schema migrations as a deployment step.

### Redis 7

- Sub-millisecond quota counters via `INCR` (ADR-0011 dual-ledger pattern).
- Rate-limit counters for multi-instance deployments
  (`RATE_LIMIT_STORE=redis`).
- Distributed locks for alert deduplication and idempotency in-flight
  protection.
- Hot-path response caching for national risk snapshots (60s TTL).

### Kafka + Debezium (CDC)

- Debezium captures PostgreSQL WAL changes and publishes them to Kafka topics.
- Downstream consumers process alert events, usage reconciliation, and
  audit-log fan-out asynchronously without coupling to the request path.
- Enables future read-model projections (Elasticsearch, materialized views)
  without modifying the write path.

### Local development

All three run in Docker Compose under the `production-data` profile:

```yaml
# docker-compose.yml (production-data profile)
services:
  postgres:
    image: timescale/timescaledb-ha:pg16
    profiles: [production-data]
    ports: ["5432:5432"]
  redis:
    image: redis:7-alpine
    profiles: [production-data]
    ports: ["6379:6379"]
  kafka:
    image: bitnami/kafka:3.7
    profiles: [production-data]
    ports: ["9092:9092"]
  debezium:
    image: debezium/connect:2.6
    profiles: [production-data]
    ports: ["8083:8083"]
```

Zero cloud cost: everything runs locally. The preview deployment continues to
use DynamoDB and does not require this profile.

## Alternatives Considered

| Option | Why not |
| --- | --- |
| **DynamoDB-only** | No SQL JOINs, no RLS, no stored procedures, no spatial indexes. Multi-entity queries require denormalization that complicates the SaaS subscription/entitlement model. |
| **MySQL 8** | No TimescaleDB extension, no native RLS (requires application-level enforcement), weaker spatial support than PostGIS. |
| **CockroachDB** | Distributed SQL with PostgreSQL wire compatibility, but operationally complex for a portfolio-scale project. No TimescaleDB extension. The distributed consensus overhead is unnecessary at current scale. |
| **Aurora PostgreSQL (cloud)** | Viable production target, but introduces a recurring cloud cost ($0.10/hr minimum for db.t4g.micro) that conflicts with the zero-idle-cost portfolio constraint. Docker Compose local PostgreSQL achieves the same schema and RLS behavior at zero cost. |
| **SQLite** | Single-writer, no RLS, no concurrent connections, no time-series support. Suitable for embedded tools, not a multi-tenant SaaS. |

## Consequences

- **Docker Compose complexity increases.** The `production-data` profile adds
  four containers (PostgreSQL, Redis, Kafka, Debezium). Startup time grows
  from ~5s to ~30s. This is acceptable for local development and CI.
- **Zero cloud cost preserved.** All containers run locally. The deployed
  preview continues to use DynamoDB. Production cloud deployment (Aurora,
  ElastiCache, MSK) is documented as a future step, not a current expense.
- **Schema migration discipline required.** Flyway migrations must be
  forward-only and zero-downtime. The migration role is separate from the
  runtime role.
- **Operational knowledge expands.** The team must understand PostgreSQL
  replication, Redis persistence semantics, Kafka consumer groups, and
  Debezium connector configuration. Runbooks cover these in
  `docs/runbooks/production-data-stack.md`.
- **CDC adds eventual consistency.** Downstream consumers see changes after
  WAL flush + Kafka publish latency (~100ms typical). Alert notifications and
  usage reconciliation tolerate this delay; the request path does not depend
  on CDC.
