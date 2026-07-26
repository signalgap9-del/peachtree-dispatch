# Production Scaling Architecture

This document describes the scaling path from a single Docker Compose instance
to a globally distributed deployment. Each level identifies what changes in
code, what changes in infrastructure, what breaks, and what to monitor.

Related decisions:
[ADR-0010](../adr/0010-production-data-stack.md) (data stack),
[ADR-0011](../adr/0011-dual-ledger-quota.md) (dual-ledger quota),
[ADR-0012](../adr/0012-alert-state-machine-in-db.md) (alert state machine),
[ADR-0013](../adr/0013-read-write-splitting.md) (read/write splitting).
[ADR-0016](../adr/0016-rag-hybrid-search.md) (RAG hybrid search).
[ADR-0020](../adr/0020-database-scale-sharding.md) (database scale & sharding).
[ADR-0021](../adr/0021-multi-tenancy-isolation.md) (multi-tenancy isolation).

---

## Level 0: Single Instance (~100 concurrent users)

**Current state.** Docker Compose runs PostgreSQL 16 (TimescaleDB), Redis 7,
and the Spring Boot application in one host. All reads and writes hit the same
PostgreSQL instance. Redis handles rate-limit counters and quota checks.
The pgvector extension stores RAG embeddings for route explanation grounding.

```
Browser ??Spring Boot ??PostgreSQL (all queries)
                     ??Redis (quota, rate limit)
```

**Code.**
- `EntitlementService.java` calls `UsageRepository.incrementAndGet()`.
- `InMemoryRateLimitRepository` or `RedisRateLimitRepository` handles rate
  limits.
- Single `DataSource` bean, no routing.
- `RagProperties` configures embedding model, dimensions, and search
  parameters. RAG is off by default (`RAG_ENABLED=false`).

**Infrastructure.**
- `docker compose --profile production-data up -d`
- One PostgreSQL container, one Redis container.
- Flyway migrations run at startup.
- pgvector extension with HNSW index on `route_risk_observation.embedding`.

**What breaks at scale.**
- PostgreSQL row lock contention on `usage_record` under concurrent quota
  increments (mitigated by Redis dual-ledger).
- Read queries (dashboard, alert history) compete with write queries
  (observations, usage upserts) for shared buffers.
- Single point of failure: PostgreSQL down = full outage.
- HNSW index build time grows with vector count. At ~100k vectors
  (384-dim), build is seconds. At ~1M, it becomes minutes.

**Monitor.**
- `pg_stat_activity` for active connections and long-running queries.
- Redis `INFO memory` and `INFO clients`.
- Spring actuator `/health` for connectivity checks.
- RAG search latency via application metrics (p95 target: < 200ms).
- `pg_stat_user_indexes` for HNSW index scan counts and sizes.

---

## Level 1: Read Scaling (~1,000 concurrent users)

**What changes.** Add a PostgreSQL streaming replica and PgBouncer. Route
read-only transactions to the replica.

```
Browser ??Spring Boot ??PgBouncer ??PG Primary (writes)
                     ??PgBouncer ??PG Replica  (reads)
                     ??Redis
```

**Code changes.**

| File | Change |
| --- | --- |
| `DataSourceConfig.java` | Define `primaryDataSource` and `replicaDataSource` beans. Wire `RoutingDataSource` with both targets. |
| `RoutingDataSource.java` | Extend `AbstractRoutingDataSource`. Return `REPLICA` when `TransactionSynchronizationManager.isCurrentTransactionReadOnly()` is true, `PRIMARY` otherwise. |
| Service classes | Annotate read-only methods with `@Transactional(readOnly = true)`. |
| `application.yml` | Add `spring.datasource.replica.*` properties. |

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

**Infrastructure changes.**
- Add `postgres-replica` container with `hot_standby = on`.
- Add PgBouncer in `transaction` pooling mode.
- Primary: `wal_level = replica`, `max_wal_senders = 4`.
- Replica: `primary_conninfo` points to primary.

**What breaks.**
- Read-after-write staleness (~100ms). A user who creates a route and
  immediately lists routes may not see it. Mitigation: the create response
  returns the full object; the frontend renders it without a follow-up query.
- PgBouncer `transaction` mode prohibits session-level `SET`. All tenant
  context must use `SET LOCAL` (transaction-scoped).
- Replica failure: `RoutingDataSource` falls back to primary. Reads continue
  at higher latency.

**Monitor.**
- `pg_stat_replication.replay_lag` on primary. Alert if > 1s for 30s.
- PgBouncer `SHOW POOLS` for active/waiting connections.
- Per-datasource HikariCP metrics (`hikaricp.connections.active` tagged by
  `primary`/`replica`).

---

## Level 2: Write Scaling (~10,000 concurrent users)

**What changes.** Offload hot-path writes from PostgreSQL. Use Redis as the
primary quota counter (dual-ledger). Use Debezium CDC ??Kafka for asynchronous
downstream processing.

```
Browser ??Spring Boot ??Redis (quota hot path)
                     ??PG Primary (business writes)
                     ??PG Replica (reads)
                          ??WAL
                       Debezium ??Kafka ??consumers
```

**Code changes.**

| File | Change |
| --- | --- |
| `RedisQuotaCounter.java` | Implements `UsageRepository`. `INCR quota:{tenantId}:{feature}:{date}` with 48h TTL. Sub-millisecond quota checks. |
| `UsageReconciliationJob.java` | `@Scheduled(fixedRate = 300000)`. Scans Redis quota keys, upserts into `usage_record` with `ON CONFLICT ... DO UPDATE SET quantity = GREATEST(...)`. |
| `AlertEventCdcConsumer.java` | Kafka consumer. Reads `atmospath.alert_event` topic (populated by Debezium). Fans out notifications, updates materialized views, triggers escalations. |
| `EntitlementService.java` | Unchanged. Still calls `UsageRepository.incrementAndGet()`. The implementation swapped from DynamoDB/PG to Redis. |

```java
// RedisQuotaCounter.java (simplified)
public long incrementAndGet(UUID tenantId, MeteredFeature feature, LocalDate date) {
    String key = "quota:%s:%s:%s".formatted(tenantId, feature.name(), date);
    long count = redisTemplate.opsForValue().increment(key);
    if (count == 1) {
        redisTemplate.expire(key, Duration.ofHours(48));
    }
    return count;
}
```

**Infrastructure changes.**
- Debezium Connect container reads PostgreSQL WAL and publishes to Kafka
  topics (`atmospath.public.alert_event`, `atmospath.public.usage_record`,
  etc.).
- Kafka (KRaft mode, single broker for local dev).
- Consumer group `atmospath-alert-consumer` for alert processing.
- Redis persistence: AOF with `appendfsync everysec`.

**What breaks.**
- Redis crash loses up to 5 minutes of quota increments (since last
  reconciliation). Tenants may get a few extra requests. Acceptable for
  preview; reduce reconciliation interval for billing-grade metering.
- CDC adds ~100ms latency between a database write and downstream consumer
  processing. Alert notifications arrive slightly later.
- Kafka consumer lag can grow if consumers are slow. Monitor consumer group
  offsets.
- Debezium requires a replication slot on the primary. Slot retention can grow
  if Kafka is down; monitor `pg_replication_slots.confirmed_flush_lsn`.

**Monitor.**
- Redis `INFO persistence` (AOF last write status).
- Kafka consumer group lag: `kafka-consumer-groups.sh --describe`.
- Debezium connector status via Connect REST API (`/connectors/*/status`).
- `pg_replication_slots` for WAL retention.
- `atmospath.quota.denials` Micrometer counter (existing).

---

## Level 3: Partitioning and Sharding (~100,000 concurrent users)

**What changes.** Partition hot tables. Prepare for horizontal sharding.

### Already implemented

| Table | Strategy | Detail |
| --- | --- | --- |
| `route_risk_observation` | TimescaleDB hypertable | 7-day chunks (V007); compression after 30 days (added by V017); retention drop after **90 days** (V007). Time-range scans touch only relevant chunks. |
| `usage_record` | Range partition by `usage_date` | Monthly partitions (V004) with on-insert auto-creation plus proactive next-month creation via pg_cron (V017). Detach/archive of partitions > 13 months is a runbook step, not yet automated. |
| `audit_log` | Plain table (gap) | **Not partitioned today** despite earlier claims in this doc; V008 creates a single append-only table. Monthly range partitioning + S3 archival are tracked in ADR-0020; V017 ships a no-op archive stub. |

```sql
-- TimescaleDB hypertable (V007; the time column is 'time', not 'observed_at')
SELECT create_hypertable(
    'route_risk_observation', 'time',
    chunk_time_interval => INTERVAL '7 days');

-- Compression policy (added by V017, not V007)
ALTER TABLE route_risk_observation SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'saved_route_id',
    timescaledb.compress_orderby = 'time DESC');
SELECT add_compression_policy('route_risk_observation', INTERVAL '30 days');

-- Retention policy (V007; 90 days, not 365)
SELECT add_retention_policy('route_risk_observation', INTERVAL '90 days');
```

### Future: tenant_id hash sharding with Citus

When a single PostgreSQL primary cannot handle the write throughput (estimated
at ~50k writes/s), shard by `tenant_id` hash using Citus:

```sql
SELECT create_distributed_table('saved_route', 'tenant_id',
       colocate_with => 'tenant');
SELECT create_distributed_table('usage_record', 'tenant_id',
       colocate_with => 'tenant');
```

**Code impact.** Queries must include `tenant_id` in the WHERE clause for
Citus to route to the correct shard. The RLS policy already filters by
`tenant_id`, so most queries are compatible. Cross-shard aggregations
(admin dashboards) use Citus coordinator queries.

**What breaks.**
- Cross-shard JOINs are slower than single-node JOINs.
- Distributed DDL requires Citus-aware migrations.
- PgBouncer must route to the Citus coordinator, not individual shards.

**Monitor.**
- Per-chunk query times via `pg_stat_statements`.
- TimescaleDB chunk exclusion: verify `EXPLAIN` shows chunk pruning.
- Partition count and size per table.

---

## Level 4: Global Distribution (future, not implemented)

**What changes.** Multi-region deployment for low-latency reads worldwide.

### Option A: Aurora Global Database

- Primary region (us-east-1) handles all writes.
- Secondary regions (eu-west-1, ap-northeast-2) host read replicas with
  < 1s cross-region replication lag.
- Application routes reads to the nearest region via Route 53 latency-based
  routing.
- Failover: promote secondary region in < 60s on primary region outage.

### Option B: CockroachDB

- Multi-region SQL with automatic geo-partitioning.
- `REGIONAL BY ROW` tables pin tenant data to the nearest region.
- `GLOBAL` tables (plan definitions, feature flags) replicate everywhere.
- No application-level read/write splitting needed; CockroachDB handles it.

### CDC cross-region replication

Regardless of database choice, Kafka MirrorMaker 2 replicates CDC topics
across regions:

```
us-east-1 Kafka ??MirrorMaker 2 ??eu-west-1 Kafka
                                ??ap-northeast-2 Kafka
```

Downstream consumers in each region process local Kafka topics, avoiding
cross-region API calls for notification delivery and audit fan-out.

**What breaks.**
- Cross-region write latency (50-150ms) if writes must go to a single primary
  region.
- Data residency requirements may prohibit certain data from leaving a region.
- Cost: multi-region Aurora or CockroachDB Dedicated is significant.
- Conflict resolution if multi-region writes are ever needed (CockroachDB
  handles this; Aurora does not support multi-region writes).

**Monitor.**
- Cross-region replication lag (Aurora: `AuroraGlobalDBReplicationLag`).
- Per-region read latency percentiles.
- Kafka MirrorMaker 2 replication latency and checkpoint lag.

---

## Scaling Decision Matrix

| Level | Concurrent users | Key change | Code impact | Infra cost (local) |
| --- | --- | --- | --- | --- |
| 0 | ~100 | Single PG + Redis | None (current) | $0 |
| 1 | ~1,000 | + Replica + PgBouncer | `RoutingDataSource`, `@Transactional(readOnly)` | $0 (Docker) |
| 2 | ~10,000 | + Redis quota + CDC | `RedisQuotaCounter`, `AlertEventCdcConsumer` | $0 (Docker) |
| 3 | ~100,000 | + Partitioning + Citus | Tenant-scoped queries, Citus DDL | $0 (Docker) / cloud $$$ |
| 4 | Global | + Multi-region | Latency routing, region-aware consumers | Cloud $$$ |

All levels up to 3 run entirely in Docker Compose at zero cloud cost. Level 4
requires managed cloud services and is documented as a future path.

---

## Vector Search Scaling

RAG embeddings live in PostgreSQL via the pgvector extension. The scaling
path for vector search follows the database scaling path until vector
count outgrows single-instance PostgreSQL.

| Vector count | Strategy | Detail |
| --- | --- | --- |
| < 1M | pgvector + HNSW | Current approach. Single PostgreSQL instance. HNSW index with `m=16, ef_construction=64`. Query latency < 50ms at 384 dimensions. |
| 1M - 10M | pgvector + partitioned index | Partition `route_risk_observation` by time (already a TimescaleDB hypertable). Each chunk gets its own HNSW index. Queries target recent chunks first. |
| 10M - 100M | pgvector on dedicated instance | Move vector workload to a separate PostgreSQL instance with higher `maintenance_work_mem` and `shared_buffers`. Keeps vector queries from competing with OLTP. |
| 100M+ | Dedicated vector DB | Evaluate Weaviate, Qdrant, or Pinecone. At this scale, HNSW memory footprint (~1.5 KB/vector at 384-dim) exceeds what a single PostgreSQL instance handles well. A purpose-built vector DB provides distributed indexing, better recall tuning, and horizontal scaling. |

**Migration path.** The `embedding_model` column on `route_risk_observation`
tracks which model produced each vector. When switching embedding models or
migrating to a new vector store, re-embed all observations and validate
retrieval quality with the golden evaluation set (`RagEvaluationService`)
before cutover.

**Code impact at migration.** `HybridSearchService` abstracts the search
backend behind an interface. Swapping pgvector for a dedicated vector DB
changes the implementation, not the callers. The RRF fusion and
cross-encoder reranking layers are backend-agnostic.

---

## Database Observability & Operations

Scaling decisions are only as good as the measurements behind them. This
section defines the standing database observability and maintenance program;
the scheduled pieces are created by migration V017 (see
[ADR-0020](../adr/0020-database-scale-sharding.md)).

### pg_stat_statements

Enabled by V017 (`CREATE EXTENSION IF NOT EXISTS pg_stat_statements`,
preloaded via `shared_preload_libraries`). The `v_query_performance` view
ranks queries by total and mean execution time and exposes `rows_per_call`,
which is the fastest way to find a missing index (a query returning 50k rows
per call to produce one screen of data is a scan that should be a seek).

- Review the top 20 by `total_ms` weekly; investigate anything new.
- Reset the baseline (`SELECT pg_stat_statements_reset();`) after each
  schema migration so pre/post comparisons are clean.
- This view is also the measurement source for the ADR-0020 sharding
  triggers (sustained ingest rate, hot query shapes).

### Slow query log

`log_min_duration_statement = 250` is set on `postgres-primary` in
`compose.yaml` (production-data profile) and should be mirrored in the RDS
parameter group. 250 ms sits above the p95 target for interactive queries
and below the level where users abandon dashboards. Anything logged at this
threshold gets an `EXPLAIN (ANALYZE, BUFFERS)` in the relevant runbook entry
before it is tuned - no intuition-only index additions.

### Autovacuum tuning

The workload has two distinct table personalities; tune per table, not
globally:

- **Append-only tables** (`audit_log`, `usage_record` partitions,
  `route_risk_observation` chunks): updates are rare, so vacuum matters less
  than statistics freshness. Raise `autovacuum_vacuum_scale_factor` (e.g.
  0.2) and keep `autovacuum_analyze_scale_factor` low (0.05) so planners see
  new data. Immutable detached partitions can have autovacuum disabled
  entirely.
- **High-churn tables** (`saved_route`, `saved_place`, `tenant_member`,
  `subscription`): `updated_at` triggers rewrite rows on every update and
  soft-delete accumulates dead tuples behind partial indexes. Leave defaults
  or lower `autovacuum_vacuum_scale_factor` (0.05) if
  `n_dead_tup` grows.

Watch `pg_stat_user_tables.n_dead_tup` and `last_autovacuum` per table;
a growing `n_dead_tup` on a hot table is index bloat in the making.

### Replication lag monitoring

Per ADR-0013 the replica is async with ~10-100 ms typical lag. Monitor:

```sql
-- On the primary: per-replica lag
SELECT application_name, state,
       pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn) AS lag_bytes,
       replay_lag
FROM pg_stat_replication;

-- On the replica: local view of replay delay
SELECT pg_last_wal_replay_lag();
```

Alert thresholds: `replay_lag > 1s` sustained for 30 s (read-staleness
budget), and any replication slot whose `confirmed_flush_lsn` stops
advancing (the Debezium slot will retain WAL while Kafka is down and can
fill the disk - `pg_replication_slots.confirmed_flush_lsn` is a disk-usage
proxy).

### Scheduled jobs (pg_cron, created by V017)

| Job | Schedule (UTC) | Purpose |
| --- | --- | --- |
| `refresh_mv_workspace_usage_summary` | every 5 min | `REFRESH MATERIALIZED VIEW CONCURRENTLY` for the usage dashboard (V012) |
| `refresh_mv_tenant_risk_summary` | hourly at :05 | Same for the risk dashboard; offset from the TimescaleDB continuous-aggregate refreshes |
| `create_usage_partitions` | daily 02:10 | Proactively creates current + next month `usage_record` partitions (V004 trigger remains the fallback) |
| `cleanup_idempotency_keys` | every 15 min | Deletes expired `idempotency_key` rows via the V011 partial index |
| `archive_audit_log` | not scheduled | No-op stub; activates with the S3 export pipeline (ADR-0020) |

TimescaleDB's own background policies run alongside these: chunk compression
after 30 days and drop after 90 days on `route_risk_observation` (V007 +
V017), and continuous-aggregate refreshes for `cagg_risk_hourly` /
`cagg_risk_daily` (V007). Do not duplicate them in pg_cron.

pg_cron requires `shared_preload_libraries` to include `pg_cron` and
`cron.database_name` to point at the app database (`atmospath`); both are
set in `compose.yaml` for the primary. The `production-data` profile uses
`timescale/timescaledb-ha:pg16` because the plain `timescale/timescaledb`
image does not bundle pg_cron; V017 detects a missing pg_cron and skips job
creation with a WARNING instead of failing the migration. If a job silently
never runs, check the preload list first - V017 logs a WARNING at migration
time when it is missing.
