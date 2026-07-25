# ADR 0013: Read/Write Splitting with Streaming Replication

## Status

Accepted

## Context

AtmosPath has an asymmetric read/write profile:

- **Read-heavy paths.** Dashboard risk summaries, saved-route lists, alert
  history, status page queries, and risk-observation time-range scans. These
  are latency-sensitive and tolerate slight staleness (~100ms).
- **Write-heavy paths.** Usage counter reconciliation, route risk observation
  ingestion, alert event creation, and audit log appends. These are
  throughput-sensitive and must be durable.

A single PostgreSQL instance handles both, but under load the write workload
(especially bulk observation inserts and usage upserts) competes with read
queries for shared buffers, WAL flush I/O, and CPU. At ~100 concurrent users
this is manageable. At 10x scale, read latency degrades because long-running
dashboard queries queue behind write transactions.

## Decision

Add a **PostgreSQL streaming replication replica** and route read-only
transactions to it via Spring's `AbstractRoutingDataSource`.

### Infrastructure

```
┌─────────────┐       WAL stream        ┌─────────────┐
│  PG Primary  │ ──────────────────────→ │ PG Replica   │
│  (writes)    │   async, ~100ms lag     │  (reads)     │
└──────┬───────┘                         └──────┬───────┘
       │                                        │
       └──────────── PgBouncer ─────────────────┘
                  (connection pooling)
```

- **Primary** handles all writes: INSERT, UPDATE, DELETE, and stored function
  calls (`transition_alert_state()`, `change_subscription_plan()`).
- **Replica** receives WAL via streaming replication (`wal_level = replica`,
  `max_wal_senders = 4`). Async replication; typical lag is 10-100ms on local
  Docker networking.
- **PgBouncer** sits in front of both, pooling connections in `transaction`
  mode. The application connects to PgBouncer, not directly to PostgreSQL.

### Application routing

`RoutingDataSource` extends `AbstractRoutingDataSource` and selects the target
based on the current transaction's read-only flag:

```java
// DataSourceConfig.java
@Bean
public DataSource routingDataSource(
        @Qualifier("primaryDataSource") DataSource primary,
        @Qualifier("replicaDataSource") DataSource replica) {
    var routing = new RoutingDataSource();
    routing.setTargetDataSources(Map.of(
        DataSourceType.PRIMARY, primary,
        DataSourceType.REPLICA, replica));
    routing.setDefaultTargetDataSource(primary);
    return routing;
}
```

```java
// RoutingDataSource.java
public class RoutingDataSource extends AbstractRoutingDataSource {
    @Override
    protected Object determineCurrentLookupKey() {
        return TransactionSynchronizationManager.isCurrentTransactionReadOnly()
            ? DataSourceType.REPLICA
            : DataSourceType.PRIMARY;
    }
}
```

Service methods annotate read-only paths:

```java
@Transactional(readOnly = true)
public List<SavedRoute> listRoutes(UUID memberId) { ... }

@Transactional  // defaults to readOnly = false → primary
public SavedRoute createRoute(CreateRouteCommand cmd) { ... }
```

### Tenant context propagation

Both primary and replica connections set the RLS tenant context at checkout:

```java
// Executed on connection checkout for both data sources
connection.createStatement().execute(
    "SET LOCAL app.tenant_id = '" + tenantId + "'");
```

RLS policies are replicated to the replica automatically (they are catalog
objects, replicated via WAL).

## Alternatives Considered

| Option | Why not |
| --- | --- |
| **Single instance (no replica)** | Simpler, but does not demonstrate read scaling. Write-heavy observation ingestion degrades read latency under concurrent load. The portfolio review benefits from showing a concrete scaling pattern. |
| **Citus distributed sharding** | Distributes writes across nodes by tenant_id hash. Operationally complex (coordinator + workers, distributed DDL constraints). Overkill for the current scale; documented as a future path in `production-scaling.md`. |
| **Application-level read cache (Redis)** | Caches specific query results but does not help ad-hoc dashboard queries or time-range scans. Adds cache invalidation complexity. Complementary to, not a replacement for, read replicas. |
| **ProxySQL / pgpool-II** | External routing proxies that split reads/writes transparently. Adds another operational component. Spring's `AbstractRoutingDataSource` achieves the same routing with zero additional infrastructure and keeps the decision visible in application code. |
| **Synchronous replication** | Guarantees zero read-after-write staleness but adds write latency (primary waits for replica ACK). The workload tolerates ~100ms staleness on reads, so async replication is the better tradeoff. |

## Consequences

- **Eventual consistency on reads (~100ms replication lag).** A user who
  creates a saved route and immediately lists routes may not see it for up to
  100ms. Mitigation: the create response returns the full object, so the
  frontend can render it without a follow-up list query.
- **Read-after-write on the same request is safe.** A single `@Transactional`
  method that writes and then reads uses the primary for the entire
  transaction. The routing decision is per-transaction, not per-query.
- **Replica failure degrades to primary-only.** If the replica is unreachable,
  `RoutingDataSource` falls back to the default (primary). Reads continue;
  latency increases but availability is preserved.
- **PgBouncer in transaction mode.** `SET LOCAL` is scoped to the transaction,
  which is compatible with PgBouncer `transaction` pooling. Session-level
  settings (`SET` without `LOCAL`) would leak across pooled connections and
  are prohibited.
- **Docker Compose adds one more container.** The `production-data` profile
  now includes `postgres-replica` with `hot_standby = on`. Startup time
  increases by ~5s for the replica to catch up.
- **Monitoring.** Replication lag is exposed via
  `pg_stat_replication.replay_lag` on the primary and
  `pg_last_wal_replay_lsn()` on the replica. Alert threshold: > 1s sustained
  for 30s.
