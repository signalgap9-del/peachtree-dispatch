# FreightScaler API Load Tests (k6)

HTTP-level load tests for the FreightScaler platform API using
[Grafana k6](https://grafana.com/docs/k6/). These complement the existing
`perf/local_api_stress.py` (in-process Python smoke) and `perf/pgbench/`
(PostgreSQL data layer) by testing the full deployed stack: CloudFront,
API Gateway, Lambda, DynamoDB, and external API integrations.

## Safety Rules

1. **STAGING ONLY.** Never run these scripts against production. The runner
   script (`run.sh`) refuses to execute if `BASE_URL` looks like production.
2. **Cost guard.** `MAX_VUS` defaults to 50 (or 5 for AI scenarios). Each
   virtual user generates real Lambda invocations and API Gateway requests
   that cost money. Raise the ceiling only with explicit intent.
3. **AI chat costs real tokens.** The `ai_chat` scenario hits the LLM
   endpoint. Keep volume low and duration short unless you are specifically
   testing AI throughput and have budget approval.

## Prerequisites

- [k6](https://grafana.com/docs/k6/latest/set-up/install-k6/) installed
- A deployed staging environment with a known URL
- (For `auth_flow`) test credentials and the Cognito token endpoint

## Quick Start

```bash
# Run the mixed realistic profile against staging
./perf/k6/run.sh mixed -e BASE_URL=https://staging.freightscaler.com

# Run only route planning with a 30-VU ceiling
./perf/k6/run.sh route_planning -e BASE_URL=https://staging.freightscaler.com -e MAX_VUS=30

# Short AI chat smoke (1 minute, 2 VUs)
./perf/k6/run.sh ai_chat -e BASE_URL=https://staging.freightscaler.com -e DURATION=1m -e MAX_VUS=2
```

## Scenarios

| Script | Traffic model | Default VUs | Think time |
|--------|--------------|-------------|------------|
| `route_planning.js` | 50% POST /directions, 30% place search, 20% risk reads | 20 peak | 10-30s |
| `ai_chat.js` | LLM chat prompts only | 3 peak | 30-60s |
| `auth_flow.js` | Cognito login + saved route reads + health | 15 peak | 5-15s |
| `mixed.js` | Blended dispatcher + AI traffic (ramping) | 25 + 3 | 5-60s |

## Thresholds

All scenarios enforce pass/fail thresholds. A test run that breaches any
threshold exits non-zero (useful for CI gates):

| Metric | Threshold | Rationale |
|--------|-----------|-----------|
| p95 latency (cached reads) | < 500 ms | CloudFront + Lambda cold start budget |
| p95 latency (route planning) | < 2000 ms | External routing API + risk engine fan-out |
| p95 latency (AI chat) | < 10000 ms | LLM inference is inherently slow |
| Error rate | < 1% | Allows transient 429s under ramp, catches real failures |

## Configuration

All configuration is via k6 environment variables (`-e KEY=VALUE`):

| Variable | Default | Description |
|----------|---------|-------------|
| `BASE_URL` | `https://staging.freightscaler.com` | Target deployment URL |
| `MAX_VUS` | `50` (5 for AI) | Hard ceiling on concurrent virtual users |
| `DURATION` | `2m` | Sustain duration for the main ramp stage |
| `AUTH_USERNAME` | (empty) | Test user email for auth_flow |
| `AUTH_PASSWORD` | (empty) | Test user password for auth_flow |
| `COGNITO_TOKEN_URL` | (empty) | Cognito OAuth2 token endpoint |

## Local Target

For local development (no AWS cost), point k6 at the local dev server:

```bash
# Start the local API
cd services/api && uvicorn app.main:app --port 8000

# Run k6 against localhost (skip CloudFront/Lambda, tests app logic only)
k6 run perf/k6/scenarios/route_planning.js -e BASE_URL=http://localhost:8000
```

Note: local runs skip CloudFront caching, Lambda cold starts, and API
Gateway throttling. Latency numbers will not match staging.

## Reading Results

k6 prints a summary table at the end of each run:

```
http_req_duration..............: avg=234ms  min=45ms  med=198ms  max=1.2s
  { endpoint:cached_read }.....: avg=89ms   min=23ms  med=67ms   max=412ms
  { endpoint:route_planning }..: avg=890ms  min=234ms med=780ms  max=2.1s
http_req_failed................: 0.00%  0 out of 1234
```

Key things to look for:

- **p(95) by endpoint tag**: are cached reads under 500ms? Is route planning
  under 2s?
- **http_req_failed**: anything above 1% indicates a real problem (not just
  throttling).
- **vus count vs latency**: if latency spikes as VUs ramp, you have found a
  concurrency bottleneck (Lambda concurrency, DynamoDB throughput, connection
  pool exhaustion).
- **checks success rate**: failed checks mean the API returned unexpected
  shapes or status codes.

## Breaking-Point Methodology

To find the system's capacity ceiling:

1. Start with `mixed.js` at default settings to establish a baseline.
2. Increase `MAX_VUS` in steps of 10 (e.g., 20, 30, 40, 50) across
   successive runs.
3. At each step, record: p95 latency, error rate, and throughput (req/s).
4. The breaking point is where either:
   - p95 latency exceeds the threshold by >50% for two consecutive steps, OR
   - error rate exceeds 5%.
5. Document the result in this README or a linked test report.

Expected breaking points (to be validated on staging):

| Component | Expected limit | Bottleneck |
|-----------|---------------|------------|
| API Gateway | ~1000 req/s | Default throttle (adjustable) |
| Lambda (API) | ~100 concurrent | Reserved concurrency setting |
| DynamoDB | ~25 RCU/WCU burst | On-demand mode auto-scales |
| CloudFront | Effectively unlimited | Edge caching absorbs reads |

## Relationship to Other Perf Tests

| Layer | Tool | What it tests |
|-------|------|---------------|
| Application logic | `perf/local_api_stress.py` | In-process latency, no cloud cost |
| Data layer | `perf/pgbench/` | PostgreSQL stored functions, indexing |
| Full stack (this) | `perf/k6/` | CloudFront + API GW + Lambda + DynamoDB |

Run the local stress test first as a release gate. Run k6 against staging
before any production promotion.
