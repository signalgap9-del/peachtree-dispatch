# ADR 0007: Use DynamoDB for Preview Persistence

## Status

Accepted

## Context

The low-traffic portfolio preview needs authenticated saved places, but an
Aurora cluster adds idle cost and Free Plan-specific provisioning behavior.
Saved places currently need only owner-scoped create, list, delete, and bounded
nearby filtering.

## Decision

Store user projections and saved places in the existing on-demand DynamoDB
table:

- `PK=USER#<userId>, SK=PROFILE` for the Cognito identity projection;
- `PK=USER#<userId>, SK=SAVED_PLACE#<savedItemId>` for saved places.

Spring Boot remains the public API and calculates nearby results over the
owner's bounded saved-place set. Aurora/PostGIS remains optional architecture
for future route history and complex spatial joins and stays validated in CI.

Routine deployment uses GitHub Actions OIDC. Local AWS browser login is not
part of the delivery path.

## Consequences

- The deployed preview has no relational database idle cost.
- User ownership is enforced by partition key construction from the JWT subject.
- Nearby filtering is intentionally bounded and must move to PostGIS before
  high-cardinality spatial workloads are introduced.
- Enabling Aurora requires an explicit cost-bearing infrastructure change.
