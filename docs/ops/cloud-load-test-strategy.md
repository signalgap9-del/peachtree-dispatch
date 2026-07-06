# Cloud Load Test Strategy

Status: planned, not executed for the current portfolio preview.

This document describes how AtmosPath should run a real cloud load test when the
project needs evidence beyond the cost-free local stress harness. The goal is to
prove operational behavior under bounded preview traffic without accidentally
turning a portfolio deployment into an expensive open-ended benchmark.

## Why Test Serverless

Serverless load testing is meaningful, but it answers different questions than a
traditional fixed-server benchmark.

For Lambda, API Gateway, CloudFront, and DynamoDB, the important questions are:

- Do p95 and p99 latencies stay within the product budget at expected traffic?
- What does the first request after an idle period look like?
- Do throttles, timeouts, and retries fail closed instead of cascading?
- Do DynamoDB partition keys and conditional writes behave under concurrency?
- Do CloudWatch alarms, budget alerts, and rollback procedures fire soon enough?
- Does the application degrade gracefully when route optimization gets slow?

The test should not try to prove unlimited scale. Lambda can scale quickly, but
the real bottlenecks for this product are data joins, route optimization,
external-data caching, DynamoDB write patterns, and cost controls.

## Test Levels

### Level 0: Local Regression Stress

Already implemented.

Command:

```powershell
python perf/local_api_stress.py --requests 180 --concurrency 8 --json-output docs/ops/local-api-stress-2026-07-04.json
```

Purpose:

- Catch API latency regressions before deploy.
- Exercise routing and risk endpoints without AWS cost.
- Identify solver bottlenecks in a deterministic development loop.

This is the default release gate.

### Level 1: Post-Deploy Smoke

Run after a dev deployment.

Shape:

- 1 to 3 authenticated users.
- 20 to 50 total API requests.
- No sustained concurrency.
- No paid third-party data calls unless cache is already warm.

Endpoints:

- `GET /api/health`
- `GET /api/risk/national`
- `GET /api/places/search`
- `POST /api/risk/location`
- `POST /api/directions`
- Saved-place and saved-route APIs with a Cognito token.

Success criteria:

- No 5xx responses.
- Authenticated write paths use the caller-owned tenant/user partition.
- CloudFront, API Gateway, Lambda, and DynamoDB logs show the same request IDs.
- CloudWatch shows no unexpected throttles or error bursts.

This is safe to run on every deploy.

### Level 2: Bounded Canary Load

Run only when the user explicitly chooses to spend a small amount of AWS budget.

Recommended tool:

- k6 or Artillery from a local machine or GitHub Actions runner.

Initial request budget:

- 300 to 1,000 total requests.
- 1 to 5 virtual users.
- 3 to 10 minute duration.
- Ramp up slowly, hold briefly, ramp down.

Traffic mix:

| API group | Share | Purpose |
| --- | ---: | --- |
| Health and config | 15% | Baseline CloudFront/API Gateway/Lambda latency |
| National risk | 20% | Cached read path and risk payload size |
| Place search | 20% | Search latency and response normalization |
| Location risk | 15% | Per-location weather/hazard scoring |
| Directions | 20% | Route scoring and risk join path |
| Saved objects | 10% | Cognito-protected DynamoDB writes and reads |

Success criteria:

- 0 unexpected 5xx responses.
- 4xx responses only for intentionally invalid or unauthenticated requests.
- p95 latency remains inside the current README performance budget for cached
  endpoints.
- No Lambda timeout.
- No DynamoDB throttling.
- No API Gateway throttling outside the configured test ceiling.
- Estimated cost remains under the pre-approved test budget.

Abort conditions:

- Any repeated 5xx spike.
- Lambda duration approaching timeout.
- DynamoDB throttling on saved-object or usage-counter tables.
- CloudWatch cost/budget alarm fires.
- External provider error rate increases.

### Level 3: Route Optimizer Endurance

Run only after the optimizer has an async job API or a persisted result cache.
The current synchronous multi-stop route path is intentionally not a good target
for large cloud load because it performs heavier route matrix and geometry work.

Shape:

- Low concurrency.
- Fixed synthetic route set.
- Separate run from normal web traffic.
- Result artifacts saved under `docs/ops/` after the run.

Success criteria:

- Jobs are accepted quickly.
- Long-running work is queued or cached instead of timing out HTTP requests.
- Repeated identical jobs hit cache or persisted results.
- Failed jobs include request IDs and clear retry semantics.

## Required Guardrails

Before any cloud load test:

- Confirm the AWS account budget alarm is active.
- Confirm Lambda reserved concurrency is set low enough for preview safety.
- Confirm API Gateway throttling is configured.
- Confirm CloudWatch dashboards include latency, errors, throttles, duration,
  memory, cold-start proxy metrics, and DynamoDB consumed capacity.
- Confirm the test uses synthetic users and synthetic routes only.
- Confirm paid external APIs are mocked, disabled, or cache-protected.
- Announce the exact request budget and stop condition before starting.

## Result Artifact

Every cloud run should produce a small result file in `docs/ops/`, for example:

```text
docs/ops/cloud-load-dev-YYYY-MM-DD.json
```

Minimum fields:

- environment
- git SHA
- CloudFront URL
- test tool and version
- request count
- virtual users
- duration
- endpoint mix
- p50, p95, p99 latency by endpoint group
- status-code distribution
- Lambda errors, throttles, timeouts
- DynamoDB throttles
- estimated AWS cost
- decision: pass, fail, or inconclusive

Do not record secrets, JWTs, cookies, authorization headers, or user PII in the
artifact.

## Current Release Decision

For phase 1, AtmosPath does not need a real cloud load test yet. The safer
release posture is:

1. Keep the local stress harness as the repeatable regression gate.
2. Run a small post-deploy smoke after GitHub Actions deploys `main`.
3. Add bounded k6 or Artillery scripts before claiming production-scale load
   evidence on the resume or README.

This keeps the project honest: the README can claim local stress coverage now,
and it can claim cloud load evidence only after a bounded run has actually been
executed and recorded.
