# AtmosPath Performance Test Harness

This folder contains the load-test suite for AtmosPath. It is intentionally separate from the app runtime so performance tooling never increases production bundle size or Lambda package size.

The default target is local only:

- Platform API: `http://localhost:8080/api/v1`
- Risk engine: `http://localhost:8000`

Remote targets are blocked unless `ALLOW_REMOTE_TARGET=true` is set. Staging-style runs also require `CONFIRM_STAGING_LOAD=true`. This is deliberate so a developer cannot accidentally burn AWS credits by pasting a CloudFront URL.

## Tool Choice

Use k6 for the first production-grade harness:

- scenarios model smoke, read-mix, route planning, and authenticated saved-route flows
- thresholds fail the run when p95 latency, error rate, or check pass rate violates the budget
- JSON summaries are written to `perf/results/`

Locust remains a good future option for long-running Python-heavy experiments, but k6 is a better fit for this repository because the first target is HTTP contract/load regression testing.

## Install k6

Windows:

```powershell
winget install k6
```

macOS:

```bash
brew install k6
```

Linux:

```bash
sudo gpg -k
curl -s https://dl.k6.io/key.gpg | sudo gpg --dearmor -o /usr/share/keyrings/k6-archive-keyring.gpg
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt update
sudo apt install k6
```

## Local Runs

Start the stack first:

```powershell
docker compose up --build
```

Smoke:

```powershell
k6 run perf/k6/scenarios/smoke.js
```

Read-heavy API mix:

```powershell
k6 run perf/k6/scenarios/api-read-mix.js
```

Route planning and route engine:

```powershell
k6 run perf/k6/scenarios/route-planning.js
```

Authenticated saved-routes flow:

```powershell
$env:AUTH_TOKEN = "<short-lived JWT>"
k6 run perf/k6/scenarios/saved-routes.js
```

Do not paste long-lived credentials into environment variables. Use a short-lived local or staging token only.

## Remote/Staging Runs

Use remote runs sparingly. Confirm the target and profile explicitly:

```powershell
$env:TARGET_URL = "https://your-preview.example.com/api"
$env:RISK_ENGINE_URL = "https://your-preview.example.com/api"
$env:PERF_PROFILE = "staging"
$env:ALLOW_REMOTE_TARGET = "true"
$env:CONFIRM_STAGING_LOAD = "true"
k6 run perf/k6/scenarios/smoke.js
```

For bigger tests, run against a staging environment with throttling, budgets, and CloudWatch alarms already enabled.

## Profiles

`PERF_PROFILE=local` is the default and keeps traffic tiny. `PERF_PROFILE=staging` raises volume enough to produce useful p95/p99 signals without acting like a public traffic launch.

Current profile intent:

| Profile | Use | Cost posture |
| --- | --- | --- |
| `local` | laptop/dev stack regression | no AWS cost |
| `ci` | optional smoke gate in CI | tiny traffic |
| `staging` | manual pre-release validation | requires explicit flags |

## Validation Without k6

The static validation script checks that all scenario files, datasets, guardrails, and thresholds are present.

```powershell
npm run validate --prefix perf
```

## Interpreting Results

The suite writes JSON summaries to `perf/results/`. Treat threshold failures as release blockers until the root cause is known:

- `http_req_failed` over budget: backend errors, auth failures, or gateway issues
- `checks` below budget: contract drift or wrong payload shape
- p95/p99 over budget: route-engine compute, network hop, or dependency latency
- saved-route failures: auth/Cognito/JWT or DynamoDB/relational store path

Keep raw reports out of Git. Only commit summarized findings in docs or pull request notes.

## Deferred GraphQL Scenario

`perf/k6/scenarios/graphql-route-engine.js` exists as an optional future harness, but it is not part of the current release gate. Product completeness is REST-first until auth, saved routes, alert search, route risk, and live data status are stable in the deployed app.
