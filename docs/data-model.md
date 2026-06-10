# DynamoDB Data Model

## Why Model Access Patterns First

DynamoDB does not support arbitrary relational queries efficiently. The table design is therefore derived from the operations the product must perform, not from a normalized entity diagram.

The MVP uses one table named `peachtree-dispatch-dev` with point-in-time recovery and a time-to-live attribute for short-lived idempotency records.

## Access Patterns

| ID | Operation | Key Strategy |
| --- | --- | --- |
| AP1 | Get one delivery and its event timeline | Query base table by delivery partition |
| AP2 | List deliveries by status, newest first | Query GSI1 |
| AP3 | List deliveries assigned to a driver by promised time | Query GSI2 |
| AP4 | List deliveries by promised date | Query GSI3 |
| AP5 | Apply a status event exactly once | Conditional write of idempotency item |
| AP6 | Update current delivery state and append event atomically | DynamoDB transaction |
| AP7 | Find failed asynchronous work | SQS DLQ, not a table scan |
| AP8 | List all deliveries newest first | Query GSI4 |
| AP9 | Store and retrieve an optimization job | Optimization job partition |

## Key Design

Every delivery uses a dedicated partition:

```text
PK = ORG#<organizationId>#DELIVERY#<deliveryId>
```

### Delivery Summary Item

```json
{
  "PK": "ORG#acme#DELIVERY#01J...",
  "SK": "META",
  "entityType": "Delivery",
  "deliveryId": "01J...",
  "organizationId": "acme",
  "status": "IN_TRANSIT",
  "driverId": "driver-42",
  "origin": { "city": "Atlanta", "state": "GA" },
  "destination": { "city": "Savannah", "state": "GA" },
  "promisedAt": "2026-06-12T18:00:00Z",
  "createdAt": "2026-06-10T12:00:00Z",
  "updatedAt": "2026-06-10T14:00:00Z",
  "version": 3,
  "GSI1PK": "ORG#acme#STATUS#IN_TRANSIT",
  "GSI1SK": "2026-06-10T14:00:00Z#01J...",
  "GSI2PK": "ORG#acme#DRIVER#driver-42",
  "GSI2SK": "2026-06-12T18:00:00Z#01J...",
  "GSI3PK": "ORG#acme#PROMISED_DATE#2026-06-12",
  "GSI3SK": "2026-06-12T18:00:00Z#01J..."
}
```

### Delivery Event Item

```json
{
  "PK": "ORG#acme#DELIVERY#01J...",
  "SK": "EVENT#2026-06-10T14:00:00Z#event-123",
  "entityType": "DeliveryEvent",
  "eventId": "event-123",
  "eventType": "DELIVERY_STATUS_CHANGED",
  "fromStatus": "PICKED_UP",
  "toStatus": "IN_TRANSIT",
  "source": "carrier-api",
  "occurredAt": "2026-06-10T14:00:00Z",
  "receivedAt": "2026-06-10T14:00:01Z",
  "correlationId": "correlation-456"
}
```

### Idempotency Item

```json
{
  "PK": "ORG#acme#IDEMPOTENCY#carrier-api#event-123",
  "SK": "LOCK",
  "entityType": "IdempotencyRecord",
  "deliveryId": "01J...",
  "createdAt": "2026-06-10T14:00:01Z",
  "expiresAt": 1791458401
}
```

## Atomic Event Processing

A status transition uses `TransactWriteItems`:

1. Conditionally create the idempotency record.
2. Update the delivery summary only when `version` matches the expected version.
3. Append the immutable delivery event.

This prevents duplicate processing and detects conflicting concurrent updates.

## Indexes

| Index | Partition Key | Sort Key | Purpose |
| --- | --- | --- | --- |
| GSI1 | organization + status | updated time + delivery ID | Operational status queue |
| GSI2 | organization + driver | promised time + delivery ID | Driver workload |
| GSI3 | organization + promised date | promised time + delivery ID | Daily dispatch board |
| GSI4 | organization deliveries | updated time + delivery ID | All-deliveries map feed |

## Consistency and Retention

- Delivery detail reads may request strong consistency from the base table.
- GSI list views are eventually consistent and may briefly lag after updates.
- Delivery events are immutable.
- Idempotency records expire after 30 days using TTL.
- Operational records remain in DynamoDB; long-term event archive to S3 is a later phase.

## Why Not PostgreSQL for the MVP

PostgreSQL would make ad hoc reporting and relational constraints easier, but a managed relational database remains active during idle periods and adds networking, patching, and connection-management concerns. The product's primary workload is key-based operational reads plus append-only events, which fits DynamoDB well.

Athena over an S3 event archive can be added later for analytical queries without forcing the operational database to serve reporting workloads.
