# ADR 0005: Spring Boot Public Platform API

## Status

Accepted

## Context

The original FastAPI service combined public HTTP endpoints, operational
delivery commands, provider adapters, risk scoring, and OR-Tools optimization.
That made a useful prototype but blurred ownership boundaries and did not
demonstrate the enterprise Java stack targeted by the portfolio.

## Decision

Use Java 21 and Spring Boot as the public platform API. Keep Python as an
internal risk and optimization engine.

Spring Boot owns:

- public API versioning and validation
- Cognito/JWT authorization
- user, saved-item, notification, and orchestration domains
- DynamoDB access and idempotent command handling
- observability and resilience policies

Python owns:

- external geospatial and hazard provider adapters
- route and segment risk calculations
- risk-adjusted travel matrix generation
- OR-Tools optimization jobs

## Consequences

- The portfolio demonstrates React, Spring Boot, Python optimization, AWS,
  event-driven architecture, and explicit service boundaries.
- Local development has one additional service.
- The Spring gateway initially delegates existing risk endpoints while domain
  capabilities migrate incrementally.
- Public production traffic must never call the internal Python service
  directly.

