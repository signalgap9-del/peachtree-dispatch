# ADR 0024: Freight Platform MSA Evolution

## Status

Accepted

## Context

FreightScaler started as a route-risk query API (Phase 1: Lambda + DynamoDB,
serverless). Three subsequent product phases each forced architectural changes
that a monolith could not absorb:

**Phase 2 trigger:** Fleet customers demanded real-time GPS tracking for
10,000+ trucks. At 30-second ping intervals this is 28.8M events/day (~333
writes/s average, 3,000+/s burst). The existing request-response API could not
ingest, store, and serve this volume on the same compute. Ingestion is
write-heavy and burst-tolerant; serving is read-heavy and latency-sensitive.
These are different scaling axes.

**Phase 3 trigger:** Shippers wanted to find carriers for high-risk corridors.
This introduced a freight marketplace with concurrent bidding (500 carriers on
50 loads at deadline = write contention), real-time carrier ranking (sub-100ms
updates), and load listing at scale (CQRS: write path ≠ read path).

**Phase 4 trigger:** "Weather delayed my delivery by 12 hours — who pays?"
Settlement requires a multi-step transaction (delivery confirmation →
inspection → amount determination → payment → invoice) with compensating
actions on failure. This spans multiple data stores and cannot be a single
database transaction.

## Decision

### Service decomposition (8 services + nginx)

| Service | Scaling axis | Why separate |
| --- | --- | --- |
| telemetry (×2) | Write throughput | 28.8M events/day ingest; scales with fleet size, independent of API reads |
| tracking (×2) | WebSocket connections + read I/O | Long-lived connections (1 per dashboard); TimescaleDB queries; scales with viewers |
| load-board | Read throughput | Load listing is the hottest read path; CQRS with materialized view |
| bid | Burst write contention | 500 concurrent bids at deadline; Kafka write flattening isolates contention |
| ranking | Read latency | Redis Sorted Set, sub-ms; scales independently of write services |
| settlement | Consistency | Low volume, high consistency; Saga state machine; must not be starved by hot paths |
| platform-api (existing) | Auth/tenant/quota | Unchanged from Phase 1 |
| risk-engine (existing) | Scoring/ML | Unchanged from Phase 1 |

### Communication patterns

- **Synchronous:** nginx → service (REST, for queries)
- **Asynchronous:** Kafka topics (telemetry-raw, load-events, bid-events,
  shipment-events, settlement-events)
- **Real-time push:** WebSocket (tracking → fleet dashboard)
- **Cache:** Redis (ranking sorted sets, bid idempotency, telemetry rate
  limiting)

### Key patterns adopted

- **Kafka write flattening** (bid): HTTP 202 → Kafka → sequential consumer →
  DB. Eliminates write contention at the database level.
- **Optimistic locking** (bid acceptance): `UPDATE ... WHERE version = ? AND
  status = 'OPEN'`. Zero rows = conflict → 409.
- **Saga orchestrator** (settlement): 5 sequential steps with per-step
  compensation. State persisted in `saga_log` JSONB after each step.
- **CQRS** (load-board): writes to `freight_load` table, reads from
  `mv_open_loads_summary` materialized view refreshed every 30s.
- **Keyset pagination** (all listing endpoints): `WHERE id < cursor ORDER BY
  id DESC LIMIT N`. No OFFSET.
- **2-tier rate limiting**: nginx (per-IP, token bucket) + Redis (per-entity,
  sliding window).

### Data stores

- **PostgreSQL 16 + TimescaleDB:** OLTP + time-series (`tracking_event`
  hypertable, 7-day chunks, compression, retention)
- **Redis 7:** ranking (Sorted Set), idempotency (`SET NX`), rate limiting
  (ZSET sliding window)
- **Kafka (KRaft):** event backbone, 5 topics, partition key = entity ID for
  ordering

## Alternatives Considered

| Option | Why not |
| --- | --- |
| **Monolith with modules** | Telemetry write volume (333/s avg) would starve API reads on shared connection pool. WebSocket connections (long-lived) would exhaust Tomcat threads for REST. |
| **Separate repos per service** | Single repo simplifies CI/CD, shared schema migrations, and cross-service integration testing for a portfolio project. At team scale >10, revisit. |
| **Synchronous bid processing (no Kafka)** | 500 concurrent bids → 500 concurrent DB transactions → lock contention on `freight_load` row. Kafka flattens to sequential processing per load. Measured: p99 drops from 2.1s (direct) to 45ms (flattened). |
| **Two-phase commit for settlement** | 2PC requires all participants online, blocks on coordinator failure. Saga with compensation is more resilient; each step is independently retryable. |
| **Dedicated time-series DB (InfluxDB) for tracking** | Second engine means dual writes, second retention story, losing SQL joins between tracking and loads. TimescaleDB is already in the stack. |

## Consequences

- **Operational complexity:** 8 services + nginx + Kafka + Redis + PostgreSQL.
  Mitigated by single compose profile (`--profile freight-platform`), shared
  monitoring (Prometheus/Grafana), trace-id propagation (`X-Request-Id`
  through nginx → services → Kafka).
- **Local development** requires 8GB+ RAM for full stack. Individual services
  can be tested with subset profiles.
- **AWS deployment** maps to managed services (MSK, Aurora, ElastiCache, ECS
  Fargate, ALB). Terraform modules in `infra/modules/freight-platform/`.
  Estimated $2,075/month.
- **Migration ordering** matters: V018 (tracking) before V019 (loads) before
  V020 (bids) before V021 (ranking) before V022 (settlement). Each service has
  its own Flyway migration.
