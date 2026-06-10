# ADR 0004: Asynchronous OR-Tools Optimization

## Status

Accepted on June 10, 2026.

## Context

Climate-aware route optimization is CPU-bound and can exceed the latency budget
of an interactive API request. The portfolio also needs to demonstrate a real
vehicle-routing solver, retry behavior, and operational failure handling without
paying for always-on compute.

## Decision

Use Google OR-Tools in a dedicated container-image Lambda worker. The API stores
an optimization job in DynamoDB and publishes its ID to SQS. The worker solves a
capacitated vehicle-routing problem with weather-risk penalties, persists the
result, and returns SQS partial batch failures for retryable errors.

The synchronous `/network` endpoint remains for the map demo. The asynchronous
`/optimizations` API is the production-oriented execution path.

## Consequences

- Optimization scales to zero and is isolated from API latency.
- SQS retries and a DLQ provide explicit failure recovery.
- Immutable ECR images make dev-to-production promotion reproducible.
- Lambda duration and memory constrain the maximum practical problem size.
- Larger workloads can later move behind the same job contract to Fargate.
