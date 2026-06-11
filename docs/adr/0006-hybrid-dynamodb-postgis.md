# ADR 0006: Use DynamoDB and PostgreSQL/PostGIS by Workload

## Status

Accepted

## Context

AtmosPath has two distinct persistence workloads:

1. short-lived, key-addressable weather ingestion, risk-processing, cache, and
   idempotency state; and
2. user-owned relational data and spatial relationships among routes, places,
   corridors, and hazard geometries.

Forcing spatial relationships into DynamoDB makes product queries awkward.
Using PostgreSQL for every bursty job or cache record adds unnecessary
connection and lifecycle overhead.

## Decision

Use DynamoDB only for:

- weather ingestion, raster-generation, and risk-calculation jobs;
- request idempotency and short-lived result caches;
- notification deduplication; and
- current weather/risk snapshot metadata.

Use optional Aurora PostgreSQL Serverless v2 with PostGIS for:

- Cognito-linked user identity projections;
- saved places, routes, corridors, and collections;
- alert subscriptions and persisted route-plan history; and
- spatial risk-exposure, proximity, overlap, and intersection queries.

Large raw/model weather data and rendered raster/tile artifacts live in S3.
Spring Boot accesses Aurora through the RDS Data API, avoiding a Lambda VPC,
NAT Gateway, and RDS Proxy. The relational store remains disabled by default.

## Consequences

- The system demonstrates intentional polyglot persistence.
- Cross-store transactions are prohibited; events and reconciliation coordinate stores.
- Data API and Aurora resume latency are acceptable for saved/history workflows,
  but synchronous risk scoring must not depend on an Aurora cold start.
- Cognito authorization is required before user-owned writes become public.
- PostGIS schema changes are versioned and validated against real PostGIS in CI.
