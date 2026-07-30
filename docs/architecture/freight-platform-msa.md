# Freight Platform MSA Architecture

This document describes the microservice architecture of the FreightScaler
freight platform (Phases 2-4). For the decision record and alternatives
analysis, see [ADR-0024](../adr/0024-freight-platform-msa-evolution.md).

## System Diagram

```
                                    ┌─────────────────────────────────────────────────────────────────┐
                                    │                        KAFKA (KRaft)                            │
                                    │                                                                 │
                                    │  ┌──────────────┐ ┌────────────┐ ┌────────────┐                │
                                    │  │ telemetry-raw│ │ load-events│ │ bid-events │                │
                                    │  └──────┬───────┘ └─────┬──────┘ └─────┬──────┘                │
                                    │         │               │              │                        │
                                    │  ┌──────┴────────┐ ┌────┴──────────────┴──────┐                │
                                    │  │shipment-events│ │    settlement-events     │                │
                                    │  └──────┬────────┘ └────────────┬─────────────┘                │
                                    └─────────┼───────────────────────┼───────────────────────────────┘
                                              │                       │
 ┌────────┐      ┌───────────────┐            │                       │
 │ Client │─────▶│ freight-nginx │            │                       │
 │(Browser│      │  (LB/WAF)     │            │                       │
 │ /App)  │      │  :80/:443     │            │                       │
 └────────┘      └───────┬───────┘            │                       │
                         │                    │                       │
          ┌──────────────┼──────────────────┐ │                       │
          │              │                  │ │                       │
          ▼              ▼                  ▼ ▼                       ▼
 ┌──────────────┐ ┌────────────┐  ┌──────────────┐  ┌──────────────────────┐
 │  telemetry   │ │  tracking  │  │  load-board  │  │     settlement       │
 │  (×2 :8081)  │ │ (×2 :8082)│  │  (:8083)     │  │     (:8086)          │
 │              │ │            │  │              │  │                      │
 │ GPS ingest   │ │ WebSocket  │  │ CQRS reads   │  │ Saga orchestrator    │
 │ rate-limit   │ │ fan-out    │  │ mat-view     │  │ 5-step compensation  │
 │ validate     │ │ TimescaleDB│  │ keyset page  │  │                      │
 └──────┬───────┘ └─────┬──────┘  └──────┬───────┘  └──────────┬───────────┘
        │               │                │                      │
        │        ┌──────┴──────┐  ┌──────┴───────┐             │
        │        │    bid      │  │   ranking    │             │
        │        │  (:8084)    │  │  (:8085)     │             │
        │        │             │  │              │             │
        │        │ 202+Kafka   │  │ Redis ZSET   │             │
        │        │ opt. lock   │  │ sub-ms reads │             │
        │        └──────┬──────┘  └──────┬───────┘             │
        │               │                │                      │
 ┌──────┴───────────────┴────────────────┴──────────────────────┴───────┐
 │                         DATA LAYER                                    │
 │                                                                       │
 │  ┌─────────────────────────────────────┐  ┌────────────────────────┐ │
 │  │     PostgreSQL 16 + TimescaleDB     │  │       Redis 7          │ │
 │  │                                     │  │                        │ │
 │  │  ┌───────────┐   ┌──────────────┐  │  │  ranking:sorted-set    │ │
 │  │  │  PRIMARY  │──▶│   REPLICA    │  │  │  bid:idempotency       │ │
 │  │  │  :5432    │   │   :5433      │  │  │  telemetry:rate-limit  │ │
 │  │  └───────────┘   └──────────────┘  │  │  session:cache         │ │
 │  │                                     │  │                        │ │
 │  │  tracking_event (hypertable)        │  └────────────────────────┘ │
 │  │  freight_load, freight_bid          │                             │
 │  │  carrier_ranking, settlement_log    │                             │
 │  │  mv_open_loads_summary (mat view)   │                             │
 │  └─────────────────────────────────────┘                             │
 └───────────────────────────────────────────────────────────────────────┘

 ┌───────────────────────────────────────────────────────────────────────┐
 │  EXISTING (Phase 1, unchanged)                                        │
 │  platform-api (:8080) — auth, tenant, quota                           │
 │  risk-engine  (:8090) — scoring, ML inference                         │
 └───────────────────────────────────────────────────────────────────────┘
```

## Service Catalog

| Service | Responsibility | Port | DB tables owned | Kafka produced | Kafka consumed | Cache strategy | Scaling unit | Replicas |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| freight-nginx | L7 load balancing, per-IP rate limiting (token bucket), TLS termination, static assets | 80, 443 | — | — | — | — | Connections | 2 |
| telemetry | GPS ping ingestion, schema validation, per-entity rate limiting, publish to Kafka | 8081 | — | telemetry-raw | — | Redis sliding-window rate limit (ZSET) | Write throughput (CPU) | 2 |
| tracking | Batch insert into TimescaleDB, WebSocket fan-out to dashboards, history queries | 8082 | tracking_event | — | telemetry-raw | Redis last-known-position cache | WebSocket connections + read I/O | 2 |
| load-board | Load CRUD, CQRS read via materialized view, keyset pagination | 8083 | freight_load, mv_open_loads_summary | load-events | — | Mat-view (30s refresh) | Read throughput | 1 |
| bid | Bid submission (202 + Kafka), bid acceptance (optimistic lock), conflict detection | 8084 | freight_bid | bid-events | bid-events (consumer) | Redis idempotency (SET NX, 5-min TTL) | Burst write contention | 1 |
| ranking | Carrier ranking computation, leaderboard queries | 8085 | carrier_ranking (audit) | — | bid-events | Redis Sorted Set (primary store) | Read latency | 1 |
| settlement | Saga orchestration: confirm → inspect → calculate → pay → invoice | 8086 | settlement_log, saga_log | settlement-events | shipment-events | — | Consistency (single writer) | 1 |
| platform-api | Auth, tenant management, quota, API keys (Phase 1) | 8080 | tenant, tenant_member, api_key, usage_record, entitlement | — | — | Redis session/quota | Request rate | 1 |
| risk-engine | Route risk scoring, ML inference, weather integration (Phase 1) | 8090 | route_risk_observation | — | — | Redis score cache | CPU (inference) | 1 |

## Kafka Topic Catalog

| Topic | Producer(s) | Consumer(s) | Partition key | Retention | Purpose |
| --- | --- | --- | --- | --- | --- |
| telemetry-raw | telemetry | tracking | vehicle_id | 24h | Raw GPS pings; decouples ingest rate from write rate |
| load-events | load-board | bid, ranking (future) | load_id | 7d | Load lifecycle (CREATED, UPDATED, CANCELLED, EXPIRED) |
| bid-events | bid (producer) | bid (consumer), ranking | load_id | 7d | Bid submissions + acceptances; sequential per-load processing |
| shipment-events | tracking, load-board | settlement | shipment_id | 7d | Shipment state transitions (PICKED_UP, IN_TRANSIT, DELIVERED, EXCEPTION) |
| settlement-events | settlement | (audit, notifications) | settlement_id | 30d | Settlement saga step completions and final outcomes |

Partition strategy: key = entity ID guarantees per-entity ordering. Partition
count = 12 (tunable); consumer group instances ≤ partition count.

## Data Flows

### GPS Ping → Dashboard

```
Truck GPS unit
    │ POST /api/v1/telemetry/ping  (30s interval)
    ▼
freight-nginx ─── per-IP token bucket ───▶ telemetry (:8081)
                                              │
                                              ├─ 1. Validate schema (lat/lon range, timestamp drift < 5 min)
                                              ├─ 2. Redis ZINCRBY rate check (per vehicle_id, 10 req/min)
                                              ├─ 3. Publish → Kafka [telemetry-raw] key=vehicle_id
                                              └─ 4. Return 204 No Content
                                                        │
                                                        ▼
                                              tracking (:8082) consumer
                                              │
                                              ├─ 5. Micro-batch (500 events or 2s, whichever first)
                                              ├─ 6. COPY INTO tracking_event (TimescaleDB hypertable)
                                              ├─ 7. Update Redis last-known-position (vehicle_id → {lat, lon, ts})
                                              └─ 8. WebSocket fan-out → subscribed dashboards
                                                        │
                                                        ▼
                                              Fleet dashboard (browser)
                                              renders marker movement in real time
```

### Load → Bid → Match

```
Shipper
    │ POST /api/v1/loads
    ▼
freight-nginx ──▶ load-board (:8083)
                    │
                    ├─ 1. INSERT freight_load (status=OPEN, version=1)
                    ├─ 2. Publish → Kafka [load-events] key=load_id {type: CREATED}
                    └─ 3. Return 201 + load_id

Carrier (browsing)
    │ GET /api/v1/loads?cursor=...&limit=20
    ▼
load-board ──▶ SELECT FROM mv_open_loads_summary (mat view, 30s refresh)
               keyset pagination: WHERE id < cursor ORDER BY id DESC LIMIT 20

Carrier (bidding)
    │ POST /api/v1/loads/{id}/bids
    ▼
freight-nginx ──▶ bid (:8084)
                    │
                    ├─ 1. Redis SET NX bid:{carrier}:{load} (idempotency, 5-min TTL)
                    ├─ 2. Publish → Kafka [bid-events] key=load_id {type: SUBMITTED}
                    └─ 3. Return 202 Accepted
                              │
                              ▼
                    bid consumer (sequential per load_id partition)
                    │
                    ├─ 4. INSERT freight_bid (status=PENDING)
                    └─ 5. If auto-match or shipper accepts:
                         UPDATE freight_load SET status='MATCHED', version=version+1
                         WHERE id=? AND version=? AND status='OPEN'
                         │
                         ├─ rows=1 → publish [bid-events] {type: BID_ACCEPTED}
                         └─ rows=0 → conflict → 409 (logged, carrier notified async)

ranking (:8085) consumes [bid-events] BID_ACCEPTED
    └─ ZINCRBY carrier_scores {carrier_id} +weight
```

### Delivery → Settlement

```
tracking publishes [shipment-events] {type: DELIVERED, shipment_id, delivered_at}
    │
    ▼
settlement (:8086) consumer
    │
    ├─ Step 1: CONFIRM_DELIVERY
    │   └─ Verify shipment status, record confirmed_at
    │   └─ Compensate: mark settlement CANCELLED
    │
    ├─ Step 2: INSPECT
    │   └─ Query tracking_event for route deviations, delays, temp excursions
    │   └─ Compensate: void inspection record
    │
    ├─ Step 3: CALCULATE_AMOUNT
    │   └─ base_rate + delay_penalty - damage_deduction = final_amount
    │   └─ Compensate: void calculation
    │
    ├─ Step 4: EXECUTE_PAYMENT
    │   └─ Debit shipper wallet, credit carrier wallet (atomic within PG)
    │   └─ Compensate: reverse transfer
    │
    └─ Step 5: GENERATE_INVOICE
        └─ INSERT invoice record, publish [settlement-events] {type: COMPLETED}
        └─ Compensate: void invoice

State: saga_log JSONB persisted after each step.
On failure: orchestrator walks completed steps in reverse, executing compensations.
```

## Failure Modes

| Component down | What happens | Impact | Mitigation |
| --- | --- | --- | --- |
| freight-nginx | All external traffic stops | Total outage | 2 replicas + health checks; ALB on AWS |
| telemetry (both) | GPS pings rejected (503) | Data loss for pings during outage | Client-side retry buffer (5 min); nginx returns 503 with Retry-After |
| Kafka | Telemetry cannot publish; bid submissions queue in-memory | Ingest pause; bid 202s delayed | KRaft 3-node quorum (AWS: MSK multi-AZ); telemetry buffers up to 10k events in-memory with backpressure |
| tracking | WebSocket connections drop; no dashboard updates | Stale positions (last Redis cache still readable) | Auto-reconnect with exponential backfill from Redis last-known-position; batch replay from Kafka on recovery |
| PostgreSQL primary | All writes fail; reads failover to replica (stale) | Write outage; reads up to replication-lag stale | Streaming replica + PgBouncer failover; Aurora multi-AZ on AWS |
| Redis | Ranking reads fail; rate limiting disabled; idempotency lost | Degraded: no ranking, possible duplicate bids, no rate limit | Sentinel failover; services degrade gracefully (ranking returns cached DB fallback, bid dedup via DB unique constraint) |
| bid service | Bid submissions return 503 | Carriers cannot bid | Single instance is the risk; scale to 2 if SLA requires; Kafka retains events so no data loss on restart |
| settlement | Saga stalls mid-flight | Payment delayed | Saga state persisted per-step; on restart, orchestrator resumes from last completed step; idempotent step execution |
| load-board | Load listing unavailable | Shippers cannot post/browse loads | Mat-view is read-only; can serve stale from replica; writes queue |

## Local vs AWS Mapping

| Compose service | AWS service | Notes |
| --- | --- | --- |
| freight-nginx | ALB + WAF | ALB handles TLS, WAF for per-IP rate limiting; nginx config maps to ALB listener rules |
| telemetry (×2) | ECS Fargate (2 tasks) | 0.5 vCPU / 1 GB each; auto-scale on CPU > 60% |
| tracking (×2) | ECS Fargate (2 tasks) | 1 vCPU / 2 GB each; sticky sessions for WebSocket; ALB WebSocket support |
| load-board | ECS Fargate (1 task) | 0.5 vCPU / 1 GB; scale on request count |
| bid | ECS Fargate (1 task) | 0.5 vCPU / 1 GB; Kafka consumer runs in same task |
| ranking | ECS Fargate (1 task) | 0.25 vCPU / 0.5 GB; lightweight Redis proxy |
| settlement | ECS Fargate (1 task) | 0.5 vCPU / 1 GB; single instance by design (saga coordination) |
| platform-api | ECS Fargate (existing) | Unchanged from Phase 1 deployment |
| risk-engine | ECS Fargate (existing) | Unchanged from Phase 1 deployment |
| PostgreSQL + TimescaleDB | Aurora PostgreSQL 16 (db.r6g.xlarge) | Multi-AZ; TimescaleDB via Aurora extension or self-managed on EC2 if extension unavailable |
| Redis 7 | ElastiCache Redis (cache.r6g.large) | Cluster mode disabled; Sentinel-equivalent via ElastiCache replication |
| Kafka (KRaft) | Amazon MSK (kafka.m5.large × 3) | Multi-AZ; KRaft mode (no ZooKeeper); 5 topics |
| Prometheus + Grafana | Amazon Managed Prometheus + Grafana | Or CloudWatch + X-Ray for simpler ops |

**Estimated monthly cost:** ~$2,075 (Fargate $420 + Aurora $680 + MSK $540 +
ElastiCache $260 + ALB $90 + data transfer/storage $85).

## Scaling Characteristics

| Service | Scaling axis | When to add instances | Bottleneck |
| --- | --- | --- | --- |
| telemetry | CPU + network I/O | Sustained ingest > 5,000 events/s per instance | Kafka producer throughput; Redis rate-limit lookups |
| tracking | WebSocket connections + disk I/O | Active connections > 5,000 per instance | TimescaleDB COPY throughput; WebSocket memory (~50 KB/connection) |
| load-board | Read throughput | p95 listing latency > 200ms | Mat-view refresh lag (30s staleness is the floor); replica read capacity |
| bid | Burst write absorption | Bid submission p99 > 100ms (202 path) | Kafka producer latency; consumer DB insert throughput |
| ranking | Read latency | Leaderboard query p99 > 5ms | Redis single-thread event loop; ZSET cardinality |
| settlement | Consistency (not throughput) | Never (single saga coordinator by design) | Step execution latency (external queries in INSPECT step) |
| platform-api | Request rate | Auth/quota p95 > 50ms | Redis session lookups; PG connection pool |
| risk-engine | CPU (ML inference) | Scoring p95 > 500ms | Model inference; embedding computation |

### Scaling triggers (measured, not predicted)

- **telemetry → 3+ instances:** when fleet size exceeds 25,000 vehicles
  (720 events/s sustained per instance at 30s interval).
- **tracking → 3+ instances:** when concurrent dashboard viewers exceed
  10,000 (WebSocket connection limit per Fargate task).
- **load-board → read replica:** when mat-view refresh contends with OLTP
  writes on primary (visible as increased lock waits in `pg_stat_activity`).
- **bid → 2 instances:** when bid submission rate exceeds 1,000/s sustained
  (Kafka producer batching becomes the bottleneck before consumer).
- **PostgreSQL → shard (ADR-0020 triggers):** sustained observation ingest
  above 50k writes/s burst, or hot+warm working set above 2 TB.
