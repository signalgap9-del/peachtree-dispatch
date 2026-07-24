# ADR 0008: Application-Level Rate Limiting for Preview

## Status

Accepted

## Context

The platform API exposes public endpoints (place search, directions, saved
places, optimization submission) through API Gateway and a Spring Boot Lambda.
These endpoints need protection from accidental or deliberate abuse, but the
portfolio preview operates under constraints:

- No AWS WAF or Shield Advanced budget; managed rate-based WAF rules add
  recurring cost disproportionate to preview traffic.
- API Gateway native throttling is coarse (per-stage or per-route) and does not
  support method+path bucket classification or per-tenant logic.
- The preview runs a single Lambda concurrency pool; multi-instance coordination
  is not yet a requirement.

## Decision

Implement fixed-window rate limiting as a Spring servlet filter in the platform
API application:

- **Bucket classification:** each request is classified by HTTP method and path
  pattern (e.g., `POST /api/routes/optimize` gets a tighter budget than
  `GET /api/places/search`).
- **Storage:** in-memory `ConcurrentHashMap` with atomic counters by default.
  A Redis-backed adapter implements the same interface for multi-instance
  deployments; it is wired via Spring profile, not code changes.
- **Response:** rejected requests receive `429 Too Many Requests` with a
  `Retry-After` header indicating seconds until the window resets.
- **Scope:** limits are per-client (Cognito subject for authenticated calls,
  source IP for unauthenticated health/search endpoints).

## Alternatives Considered

| Option | Why not |
|--------|---------|
| API Gateway native throttling | Per-stage burst/rate only; no method+path buckets, no per-tenant keys, and changes require Terraform redeploy. |
| AWS WAF rate-based rules | Adds ~$5/month base cost plus rule costs; less flexible bucket logic; overkill for preview volume. |
| Redis-only from day one | Introduces ElastiCache or Upstash cost and operational overhead before multi-instance scaling is needed. |
| No rate limiting | Leaves endpoints open to runaway loops or abuse; unacceptable even at preview scale. |

## Consequences

- In default in-memory mode, counters reset on Lambda cold start and are not
  shared across concurrent instances. This is acceptable for single-instance
  preview traffic; the Redis adapter path is ready when concurrency scales.
- Rate-limit state lives in application memory, so it adds negligible latency
  and no external dependency in the default profile.
- Bucket thresholds are configured in `application.yml`, tunable without code
  changes or infrastructure deploys.
- If the preview later moves to multiple always-warm instances, switch the
  Spring profile to activate the Redis adapter; no filter or controller changes
  are required.
