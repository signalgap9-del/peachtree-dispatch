# ADR 0009: Idempotency-Key Design for Mutating Endpoints

## Status

Accepted

## Context

The frontend retries mutating requests (save route, save place, submit
optimization) on transient network failures. Without idempotency protection, a
retry after a timeout that actually succeeded server-side creates duplicate
saved routes or places. DynamoDB's conditional writes can prevent exact-key
duplicates, but the client generates no stable resource identifier before the
first attempt, so a naive retry produces a new item.

## Decision

Require an `Idempotency-Key` header on all mutating endpoints:

- **Key generation:** the frontend generates a UUID v4 per logical operation
  and attaches it to the request. Retries reuse the same key.
- **Hashing:** the server SHA-256 hashes the raw key before storage. This
  normalizes arbitrarily long or unexpected client-supplied strings into a
  fixed 64-character hex value suitable for a DynamoDB sort key.
- **Scoping:** keys are stored as
  `PK=USER#<cognitoSub>, SK=IDEMPOTENCY#<sha256hex>` with the operation type
  and response payload in attributes. This scopes keys per tenant and prevents
  cross-user collisions.
- **Behavior on retry:** if a matching key exists, the server returns the
  stored response (same status code and body) without re-executing the
  operation.
- **TTL:** keys carry an `expiresAt` attribute (24 hours) cleaned up by the
  table's DynamoDB TTL mechanism.

## Alternatives Considered

| Option | Why not |
|--------|---------|
| Client-generated UUIDs as resource IDs | Leaks client implementation into the data model; couples resource identity to transport concerns; complicates server-side ID schemes. |
| Database unique constraint on natural key | Prevents duplicates but returns a conflict error instead of the original response; the client cannot distinguish "already done" from "genuinely conflicting." |
| No idempotency handling | Accepts duplicate saved places and routes on every retry; degrades data quality and user trust. |
| Server-side deduplication by content hash | Breaks when the same legitimate operation is intentionally repeated (e.g., saving the same place for a different trip). |

## Why Hash the Key

Raw client keys could be arbitrarily long, contain Unicode, or include
characters that complicate DynamoDB key validation. SHA-256 hashing:

- Produces a fixed-length, URL-safe hex string.
- Makes storage cost predictable regardless of client key format.
- Collision probability is negligible (2^-128 for practical input sizes).

## Consequences

- The frontend must generate and persist a key per operation attempt until it
  receives a definitive response; this is a small client-side change.
- Idempotency records consume DynamoDB storage until TTL expiry; at preview
  scale this is negligible, and the existing TTL configuration handles cleanup.
- The 24-hour window means a retry after 24 hours is treated as a new
  operation. This is acceptable because the frontend does not retry after that
  interval.
- Key collision probability with SHA-256 is cryptographically negligible; no
  additional collision-handling logic is warranted.
