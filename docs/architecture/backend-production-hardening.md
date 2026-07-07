# Backend Production Hardening

This document tracks practical backend controls that make AtmosPath reviewable
as an operations-ready Spring service, not just a demo UI.

## Current Backend Controls

| Area | Implementation | Evidence |
| --- | --- | --- |
| Auth boundary | Cognito JWT resource server path for `/api/v1/me/**` | `SecurityConfig`, controller tests |
| Ownership | Saved data is keyed by authenticated user and conditionally deleted | DynamoDB repository tests |
| Idempotency | Mutation retries use tenant-scoped hashed `Idempotency-Key` records | `IdempotencyService`, DynamoDB tests |
| Quotas | Plan and usage limits enforce route/search/location/saved capacity | `EntitlementServiceTests` |
| Rate limiting | Spring filter applies fixed-window limits before expensive route/risk calls | `RateLimitFilterTests` |
| Optional Redis | `RATE_LIMIT_STORE=redis` switches rate counters from in-memory to Redis | `RedisRateLimitRepository` |
| Metrics | Rate-limit allow/deny outcomes are counted by bucket | `atmospath.rate_limit.requests` |
| Request tracing | `X-Request-Id` is propagated through responses and error envelopes | `RequestIdFilterTests` |
| Origin protection | CloudFront origin verification rejects direct origin traffic when configured | `OriginVerificationFilterTests` |
| Structured errors | API errors include code, message, requestId, and safe details | `GlobalApiExceptionHandler` |

## Rate Limit Design

The default preview uses an in-memory fixed-window repository so the deployed
portfolio environment does not require an always-on Redis bill. The interface is
designed so production traffic can move to Redis without changing controller
code.

```mermaid
flowchart LR
  Request["HTTP request"] --> Filter["RateLimitFilter"]
  Filter --> Bucket{"Tracked path?"}
  Bucket -->|"risk reads"| Public["public-risk-read"]
  Bucket -->|"search/routes/location"| Mutation["route-risk-mutation"]
  Bucket -->|"saved/account"| Auth["authenticated-me"]
  Public --> Store["RateLimitRepository"]
  Mutation --> Store
  Auth --> Store
  Store -->|"allowed"| Controller["Controller"]
  Store -->|"exhausted"| Error["429 structured error + Retry-After"]
```

Defaults:

- public risk reads: 180 requests/minute
- route/search/location calls: 30 requests/minute
- authenticated `/me/**` calls: 90 requests/minute
- window: 1 minute
- metric: `atmospath.rate_limit.requests{bucket,outcome}`

Configuration:

```text
RATE_LIMIT_ENABLED=true
RATE_LIMIT_STORE=memory
RATE_LIMIT_STORE=redis
RATE_LIMIT_PUBLIC_READ_PER_MINUTE=180
RATE_LIMIT_MUTATION_PER_MINUTE=30
RATE_LIMIT_AUTHENTICATED_MUTATION_PER_MINUTE=90
RATE_LIMIT_WINDOW=PT1M
```

## Next Backend Hardening Slices

1. Add a local PostgreSQL/JPA slice for route observation persistence behind a
   non-default profile, with Testcontainers when Docker is available and a
   fallback unit test for CI environments without Docker.
2. Move saved-route create/update orchestration from controllers into a
   transaction-oriented service layer so capacity, idempotency, and persistence
   boundaries are easier to review.
3. Add concurrency tests for duplicate idempotency keys and saved-route capacity
   exhaustion.
4. Add Micrometer counters for rate-limit allow/deny outcomes, quota denials,
   and idempotency hits.

The roadmap intentionally avoids enabling always-on Kubernetes, Aurora, or
managed Redis in the default preview environment.
