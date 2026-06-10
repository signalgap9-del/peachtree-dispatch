# Product Requirements

## Problem

Small delivery operations teams need one place to see active deliveries, understand their event history, and recover failed status updates without inspecting cloud infrastructure directly.

Peachtree Dispatch demonstrates the engineering behind that operational workflow. It is not intended to compete with a full transportation management system.

## Users

- **Dispatcher:** creates deliveries, assigns drivers, and monitors active work.
- **Operations analyst:** filters delayed or failed deliveries and reviews timelines.
- **Platform operator:** investigates failed events, replays them, and monitors service health.
- **External integration:** submits delivery status events using an idempotency key.

## Core User Stories

1. A dispatcher creates a delivery with origin, destination, promised delivery time, and optional driver assignment.
2. An external integration submits a status event such as `PICKED_UP`, `IN_TRANSIT`, `DELIVERED`, or `FAILED`.
3. An operator lists deliveries by status, driver, or promised date.
4. An operator opens one delivery and sees its ordered event timeline.
5. A duplicate external event is accepted without changing state twice.
6. A failed asynchronous event enters a dead-letter queue and can be replayed safely.
7. An operator can see API health, workflow latency, error count, and DLQ depth.

Supported delivery status transitions and the API boundary are defined in [domain-model.md](domain-model.md).

## Scope

### MVP

- Single organization with organization-aware keys for future tenancy
- Delivery create/read/list operations
- Driver assignment
- Status event ingestion
- Ordered event timeline
- Idempotent event processing
- Failed-event DLQ and replay
- Operational dashboard and alarms

### Later

- Multi-organization onboarding
- CSV report generation using an on-demand ECS Fargate task
- Event archive in S3 and Athena operational analytics
- Synthetic load and failure injection

### Non-Goals

- Real-time GPS tracking
- Route optimization
- Payments or billing
- Native mobile applications
- Multi-region active-active deployment
- Always-on Kubernetes

## Non-Functional Requirements

| Area | Target |
| --- | --- |
| API availability | 99.9% monthly |
| API latency | p95 under 500 ms for reads and writes |
| Event processing | 99% of accepted events reflected within 60 seconds |
| Data durability | DynamoDB point-in-time recovery enabled |
| Recovery | RPO under 5 minutes, RTO under 60 minutes for documented recovery exercise |
| Security | No long-lived AWS credentials in GitHub; least-privilege runtime roles |
| Cost | Target under $10/month at portfolio traffic; hard review above $20/month |
| Operations | Structured logs, metrics, traces, alarms, DLQ replay runbook |

## Assumed Portfolio Traffic

- 100 deliveries created per day
- 1,000 status events per day
- 5,000 API reads per day
- Bursty traffic with long idle periods
- Less than 1 GB of operational data in the first year

These assumptions intentionally favor scale-to-zero compute and request-based storage pricing.
