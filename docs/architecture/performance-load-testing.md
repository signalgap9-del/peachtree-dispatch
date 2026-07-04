# Performance and Load Testing

## Status

Accepted as the first load-testing harness for the portfolio release path.

## Decision

Use k6 scripts under `perf/` for REST API, route-engine, and authenticated saved-route performance tests.

The default target is localhost and the scripts block remote targets unless `ALLOW_REMOTE_TARGET=true` is set. Staging profiles also require `CONFIRM_STAGING_LOAD=true`.

## Why k6 First

Official k6 guidance treats thresholds as pass/fail criteria for metrics such as error rate and p95 latency, which maps directly to release gates for this project. k6 scenarios also support controlled models such as ramping VUs and constant arrival rate, so we can separate read-heavy dashboard traffic from route-planning compute traffic.

Locust remains useful for future Python-centric distributed experiments, but the first need is repeatable HTTP contract and latency regression testing that can run from a developer machine without new cloud infrastructure.

Sources:

- Grafana k6 thresholds: https://grafana.com/docs/k6/latest/using-k6/thresholds/
- Grafana k6 scenario examples: https://grafana.com/docs/k6/latest/using-k6/scenarios/advanced-examples/
- Grafana k6 options reference: https://grafana.com/docs/k6/latest/using-k6/k6-options/reference/
- Locust user behavior model: https://docs.locust.io/en/stable/writing-a-locustfile.html
- AWS Distributed Load Testing cost notes: https://docs.aws.amazon.com/solutions/latest/distributed-load-testing-on-aws/cost.html

## Test Coverage

| Scenario | File | Purpose |
| --- | --- | --- |
| Smoke | `perf/k6/scenarios/smoke.js` | Health, national risk, road events |
| API read mix | `perf/k6/scenarios/api-read-mix.js` | Dashboard/search/read-heavy traffic |
| Route planning | `perf/k6/scenarios/route-planning.js` | Directions, multi-stop planning, optimization |
| Saved routes | `perf/k6/scenarios/saved-routes.js` | Authenticated create/read/history/delete lifecycle |

GraphQL route-engine testing is intentionally deferred. The optional
`perf/k6/scenarios/graphql-route-engine.js` file can be revived after the
REST-first product flows are stable.

## Initial SLO Budgets

These are not final business SLOs. They are release gates for the current preview architecture:

| Endpoint class | Local p95 | Staging p95 |
| --- | ---: | ---: |
| Health | 300 ms | 400 ms |
| Read APIs | 1200 ms | 1000 ms |
| Route planning | 3500 ms | 3000 ms |
| Saved routes | 1800 ms | 1600 ms |

Error-rate gates:

- local `http_req_failed < 2%`
- staging `http_req_failed < 1%`
- checks pass rate above 97-98%

## Cost Guardrails

The harness is designed to avoid accidental spend:

- default URLs are localhost
- remote URLs fail unless `ALLOW_REMOTE_TARGET=true`
- staging and production-like profiles need explicit confirmation flags
- no AWS Distributed Load Testing stack is deployed by default
- raw outputs are ignored by Git

Before running against AWS, verify:

- AWS Budget alert exists
- API Gateway throttling is enabled
- Lambda reserved concurrency is capped
- CloudWatch alarms cover Lambda errors, API 5xx, and DLQ depth
- the target environment is not production unless an approved release window exists

## Runbook

Local smoke:

```powershell
docker compose up --build
k6 run perf/k6/scenarios/smoke.js
```

Local route-engine pressure:

```powershell
k6 run perf/k6/scenarios/route-planning.js
```

Staging smoke:

```powershell
$env:TARGET_URL = "https://your-preview.example.com/api"
$env:RISK_ENGINE_URL = "https://your-preview.example.com/api"
$env:PERF_PROFILE = "staging"
$env:ALLOW_REMOTE_TARGET = "true"
$env:CONFIRM_STAGING_LOAD = "true"
k6 run perf/k6/scenarios/smoke.js
```

## Release Use

Run the smoke scenario before every preview publish. Run route planning before describing route-engine work on a resume or public demo. Run saved-routes only when a short-lived JWT is available and the target environment can safely create and delete user records. Do not treat GraphQL as a release blocker until the REST product surface is complete.
