# ADR 0003: Use DynamoDB as the Operational Store

## Status

Accepted

## Context

The MVP primarily reads deliveries by identifier, status, driver, and promised date, while appending immutable status events. Traffic is low and bursty, and the persistent environment should remain inexpensive.

## Options Considered

- **PostgreSQL on RDS or Aurora:** familiar relational model and flexible reporting, but adds idle cost, networking, connection management, and maintenance.
- **DynamoDB:** request-oriented modeling, scale-to-zero behavior, conditional writes, transactions, streams, and low operational overhead.

## Decision

Use a DynamoDB single-table design for current delivery state, event timelines, and idempotency records. Derive keys and indexes from documented access patterns.

Use S3 plus Athena later for analytical reporting instead of adding arbitrary scans to the operational table.

## Consequences

- Access patterns must be decided before implementation.
- Duplicate and concurrent events can be handled with conditional transactional writes.
- New query patterns may require a new index or projection pipeline.
- Developers must understand eventual consistency on global secondary indexes.
