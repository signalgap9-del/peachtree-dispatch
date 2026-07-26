# ADR 0020: Database Scale & Sharding Strategy

## Status

Accepted

## Context

FreightScaler's data growth is asymmetric. The relational schema
([`relational-data-model.md`](../relational-data-model.md), migrations
V001-V016) contains three high-volume tables and a long tail of small ones:

- **`route_risk_observation`** (V007): time-series risk scores per monitored
  route, ingested by the proactive risk engine (ADR-0018). This is the
  dominant writer. It is a TimescaleDB hypertable with 7-day chunks,
  continuous aggregates (`cagg_risk_hourly`, `cagg_risk_daily`), and a 90-day
  retention policy that drops whole chunks. V013 adds a `vector(384)`
  embedding column with an HNSW index.
- **`usage_record`** (V004): quota metering, natively partitioned by
  `RANGE (usage_date)` into monthly partitions, with a trigger
  (`create_usage_partition_if_missing()`) that creates partitions on demand.
- **`audit_log`** (V008): trigger-written, append-only, stores full
  `to_jsonb(OLD/NEW)` row images in `changes`. **It is a plain table today** -
  no partitioning, no retention. (`production-scaling.md` previously listed it
  as partitioned; that described intent, not the migration.)

Everything else (`tenant`, `tenant_member`, `workspace`, `subscription`,
`entitlement`, `saved_route`, `saved_place`, `alert_event`,
`alert_escalation`, `idempotency_key`, `api_key`) is small relative to the
big three: row counts scale with users, not with request volume.

Two accuracy notes that shape this decision:

1. `production-scaling.md` Level 3 claims a TimescaleDB compression policy on
   `route_risk_observation`. V007 does not create one. V017 (this decision)
   adds it; until then the 90-day window is stored uncompressed.
2. `route_risk_observation` has **no `tenant_id` column**; it reaches the
   tenant only via `saved_route.member_id -> tenant_member.tenant_id`. This
   matters for shard routing (see below).

### Capacity model

Sizing assumptions (self-serve SaaS; 1 user ~ 1 tenant for free/pro, N
members per tenant for Team):

- 3 monitored routes per active user on average.
- 24 observations per monitored route per day (15-minute evaluation windows
  over ~6 active hours, plus weather-driven bursts).
- ~20 metered API calls per user per day; the Redis dual-ledger
  reconciliation job (ADR-0011) collapses these to ~3 `usage_record` rows per
  user per day (one per feature).
- ~10 audited writes per user per day (saved route/place CRUD, subscription
  changes, alert lifecycle).
- Row widths: observation ~300 B base (time, UUID, score, level, small JSONB
  `factors`), **+1.55 KB when `embedding` is populated** (384 x 4 B + header);
  `audit_log` 0.8-2 KB (full row images); `usage_record` ~100 B;
  `saved_route`/`saved_place` ~0.5 KB.

| Table | 1k users | 100k users | 1M users |
| --- | --- | --- | --- |
| `route_risk_observation` | 72k rows/day; ~6.5M steady-state under 90-day retention (~2 GB base / ~12 GB fully embedded) | 7.2M rows/day; ~650M steady-state (~195 GB / ~1.2 TB embedded) | 72M rows/day; ~6.5B steady-state (~1.9 TB / ~12 TB embedded) |
| `usage_record` | 3k rows/day; trivial | 300k rows/day; ~9M rows/month (~0.9 GB/month, partitioned) | 3M rows/day; ~90M rows/month (~9 GB/month, partitioned) |
| `audit_log` | 10k rows/day; ~3.7M/year (~5 GB) | 1M rows/day; ~365M/year (~500 GB/year, unpartitioned today) | 10M rows/day; ~3.65B/year (~5 TB/year) |
| `saved_route` / `saved_place` | ~5k total | ~500k total | ~5M total |
| `alert_event` | ~1-2k/day | ~150k/day; ~50M/year | ~1.5M/day; ~500M/year |

Embeddings are only written when RAG is enabled (`RAG_ENABLED=false` by
default), so the base column is the planning case; the embedded column is the
worst case.

### Where a single database breaks first

1. **`route_risk_observation` breaks on storage and write throughput.**
   Without compression, the 90-day window at ~100k users is ~650M rows /
   ~195 GB plus the per-chunk btree and HNSW indexes. Symptoms arrive in
   order: chunk index working set exceeds `shared_buffers` (time-range scans
   turn to random I/O); HNSW index maintenance dominates insert cost (at
   384 dims, ~1.5 KB index entry per vector); autovacuum contends on chunks
   that receive late-arriving updates. Average ingest at 1M users is
   ~830 rows/s with 5-10x storm bursts - within a large single instance's
   reach *only* with compression and chunk pruning doing their jobs.
2. **`audit_log` breaks on query latency and write amplification.** A plain
   BIGSERIAL table at ~500 GB/year (100k users) grows a deep
   `(tenant_id, created_at)` index; tenant history scans slow down, and every
   audited write pays the `to_jsonb(OLD/NEW)` serialization cost on the write
   path. Vacuum pressure is low (append-only) but there is no mechanism to
   ever remove or archive rows, so the table only grows.
3. **`usage_record` is the table that does *not* break**: monthly range
   partitions keep indexes small and make old data detachable. It needs an
   archival step (detach + export partitions > 13 months), not a redesign.

Vertical scaling buys real runway. A single large instance (e.g. 8 vCPU /
64 GB, gp3 storage) with compression, retention, and one read replica carries
the ~100k-user case comfortably and plausibly reaches ~1M users for the OLTP
tables before write throughput or storage IOPS become hard limits.

## Decision

### Now: single primary + replica, partitioning, compression, retention, observability

Keep the current topology (ADR-0013): one PostgreSQL 16 + TimescaleDB
primary, one streaming replica, PgBouncer. Make the data tier actually
operate itself:

- **V017 operational automation** (this ADR's executing migration):
  `pg_stat_statements` for query monitoring; `pg_cron` jobs to refresh the
  V012 materialized views (`mv_workspace_usage_summary` every 5 min,
  `mv_tenant_risk_summary` hourly) `CONCURRENTLY`; proactive next-month
  `usage_record` partition creation; `idempotency_key` TTL cleanup (the
  `expires_at` column and partial index from V011 finally get a sweeper).
- **TimescaleDB compression on `route_risk_observation`** (added in V017,
  closing the gap above): `compress_segmentby = 'saved_route_id'`,
  `compress_orderby = 'time DESC'`, compress chunks older than 30 days.
  Combined with the existing 90-day drop policy this gives the tiering below.
- **Retention windows as policy**, documented in
  [`data-retention.md`](../data-retention.md) and enforced by the mechanisms
  above. `audit_log` archival ships as a **no-op stub** in V017; it activates
  only when the S3 export pipeline exists.
- **Replica and vacuum monitoring** per
  [`production-scaling.md`](../architecture/production-scaling.md)
  (new "Database observability & operations" section).

### Tiering: hot / warm / cold

| Tier | Data | Mechanism | Window |
| --- | --- | --- | --- |
| Hot | Recent observations, uncompressed chunks | Hypertable, all indexes live | 0-30 days |
| Warm | Older observations, columnar-compressed chunks (~10-20x smaller) | TimescaleDB compression policy (V017) | 30-90 days |
| Cold | Exported parquet/CSV in S3, then chunk drop | `COPY` to S3 before `drop_chunks` (pipeline TBD; retention drop already enforced by V007) | 90 days - 2 years |
| `usage_record` | Monthly partitions | Detach + export partitions > 13 months (manual/runbook, then cron) | 13 months online, 7 years cold (billing evidence) |
| `audit_log` | Plain table today | No-op archive stub in V017; target: monthly range partitions + S3 export > 2 years | 2 years online, 7 years cold |

Compression caveat: a compressed chunk cannot serve the HNSW embedding index
without decompression. This is acceptable because RAG grounding queries
target recent observations (ADR-0016/0018); if ANN search over warm history
becomes a requirement, exclude embedding-bearing chunks from compression or
move vectors to a dedicated store (see the vector scaling table in
`production-scaling.md`).

### Later: shard by `tenant_id` - triggers and shape

Sharding is deferred until one of these triggers is met, measured - not
predicted - via `pg_stat_statements` and instance metrics:

- Sustained observation ingest above **~50k writes/s** (burst), or
- Hot + warm working set above **~2 TB** on the primary, or storage IOPS
  saturation that vertical scaling cannot resolve, or
- An enterprise/compliance requirement for per-tenant physical isolation
  (see ADR-0021; this trigger can arrive before the technical ones).

When triggered, the shard key is **`tenant_id`**:

- Every OLTP access path is already tenant-scoped. RLS (V009) forces
  `tenant_id` (or a `tenant_member` join) into every query, so the shard key
  is already in the predicate; routing is a hash of a value the application
  always has.
- Colocation keeps the join graph inside one shard: `tenant -> tenant_member
  -> saved_route/saved_place`, `tenant -> subscription -> entitlement`,
  `tenant -> usage_record`, `tenant -> audit_log`, `tenant -> alert_event`.
- **Known prerequisite:** `route_risk_observation` must gain a denormalized
  `tenant_id` column (backfilled via the `saved_route` join) before it can be
  distributed. Without it, every observation write and time-range query would
  need a cross-node lookup to find its shard. This is a small, safe migration
  and also simplifies RLS, which currently reaches the tenant through an
  `EXISTS` subquery.
- **Cross-shard query problem.** The two reporting materialized views and any
  fleet-wide analytics span all tenants. These must not run on the shard
  coordinator at scale; they move to the read-side pipeline that Level 2
  already introduces (Debezium CDC -> Kafka -> warehouse/S3), and the MVIEWs
  are rebuilt there or pinned to a dedicated analytics replica. Small
  admin/support lookups stay on the coordinator and accept fan-out cost.
- Candidate implementation is Citus (coordinator + workers) as sketched in
  `production-scaling.md` Level 3, or partitioned Aurora if we are on AWS by
  then; the tenant-keyed shape is the same either way.

### Read scaling

The streaming replica (ADR-0013) plus the materialized views and TimescaleDB
continuous aggregates already form a read model: dashboards read pre-aggregated
data, not base tables. This is CQRS in everything but name. A formal CQRS
split (separate read datastore) is only warranted if the join cost of
refreshing the read model or the staleness tolerance of dashboards breaks -
neither is near at current scale.

## Alternatives Considered

| Option | Why not |
| --- | --- |
| **Shard from day one (Citus)** | Cross-shard analytics pain, distributed-DDL operational cost, and a coordinator hop on every query - paid upfront for a problem the capacity model puts 2-3 growth stages away. RLS + partitioning + compression + replica cover the realistic near-term load. |
| **Shard key = time for observations** | Time is already the physical layout (hypertable chunks). The access pattern is tenant/route-scoped, so time sharding would scatter one tenant's data across every node and make tenant erasure (GDPR) a fleet-wide operation. |
| **Dedicated time-series DB (InfluxDB/QuestDB) for observations** | A second engine means dual writes, a second retention story, and losing SQL joins between observations and routes/alerts. TimescaleDB is already in the stack and gives us compression, retention, and continuous aggregates on the same engine. Revisit only if single-engine write throughput demonstrably caps out. |
| **No retention; store everything** | At ~1.5 KB per embedded observation, "keep everything" is ~12 TB at 1M users for data whose query demand is overwhelmingly < 90 days old. Cold S3 storage at ~1/20th the cost of gp3 is the right home for long tails. |
| **Vertical scaling only, forever** | Valid longer than expected (~1M users for OLTP), but has a hard ceiling (IOPS, single-writer throughput, blast radius). Sharding remains the documented endgame; this ADR defines the triggers so the switch is measured, not vibes-driven. |

## Consequences

- The near-term system stays operationally simple: one primary, one replica,
  PgBouncer, Flyway. The cost is that all write scaling levers before
  sharding are finite (bigger instance, compression ratio, replica count).
- `route_risk_observation` storage at 100k users drops from ~195 GB to
  roughly ~20-30 GB hot+warm with compression, moving the sharding trigger
  from "storage" to "write throughput" - which arrives later.
- V017's cron jobs and `pg_stat_statements` require
  `shared_preload_libraries = timescaledb,pg_stat_statements,pg_cron` and
  `cron.database_name = atmospath` (set in `compose.yaml` for the
  `production-data` profile; on RDS/Aurora these go in the parameter group).
  Environments that skip the preload get working DDL but silently unscheduled
  jobs - V017 emits a warning when it detects this.
- Materialized-view refresh runs as the migration role, which bypasses RLS
  (superuser in Docker dev, `rds_superuser` on AWS). If migrations ever move
  to a least-privilege role, that role needs `BYPASSRLS` or the views will
  refresh empty under `FORCE ROW LEVEL SECURITY`.
- `audit_log` remains the weakest table: unpartitioned, unretained. The
  no-op archive stub and this ADR make the gap explicit and owned; the
  follow-up is monthly range partitions + S3 export, sequenced before the
  ~100k-user stage.
- Sharding later requires the `tenant_id` denormalization on
  `route_risk_observation` first; recording it now means it can land as a
  quiet, backfilled migration at any time without design debate.
- GDPR erasure stays a single-database operation for now (see
  [`data-retention.md`](../data-retention.md)); post-shard, erasure fans out
  to one shard per tenant, which tenant-keyed sharding keeps to exactly one
  shard.
