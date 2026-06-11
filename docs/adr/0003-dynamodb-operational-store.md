# ADR 0003: Legacy Dispatch DynamoDB Model

## Status

Superseded by [ADR 0006](0006-hybrid-dynamodb-postgis.md).

## Context

The repository began as a delivery-dispatch prototype. Its DynamoDB table and
four GSIs were designed for deliveries, drivers, promised dates, and event
timelines. AtmosPath is now a nationwide weather-risk navigation product, so
those access patterns no longer define the product architecture.

## Decision

Retain the deployed table temporarily to avoid a destructive migration.
Do not add new product features to its legacy delivery access patterns.

New DynamoDB usage is limited to weather/risk operational jobs, idempotency,
TTL caches, notification deduplication, and current snapshot metadata. Durable
user and spatial data belongs in PostgreSQL/PostGIS.

## Consequences

- Legacy GSIs remain until a separately reviewed migration removes them.
- New access patterns are documented in [the current data model](../data-model.md).
- Delivery/dispatch behavior is not exposed by the Spring public API or web client.
