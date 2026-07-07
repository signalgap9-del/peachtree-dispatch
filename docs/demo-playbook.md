# AtmosPath Demo Playbook

Date: 2026-07-07

This playbook is the two-to-ten minute path for showing AtmosPath as a real
portfolio SaaS preview without creating surprise cloud cost.

## Demo Goal

Show that the product is more than a static map:

- it can plan weather-aware routes;
- it can search and explain official hazards;
- it can save and monitor private routes;
- it has SaaS-style auth, quotas, operational status, and release gates;
- it has a guarded ML workflow that can affect route cost only after promotion.

## Two-Minute Interview Demo

Use this when the interviewer says "show me what it does."

### 0:00-0:20 - Frame the Product

Open `https://d23c97ytqgl4xu.cloudfront.net/`.

Say:

> AtmosPath is a weather-aware navigation SaaS preview. Instead of optimizing
> only for fastest route, it compares route alternatives against weather,
> official alerts, and road-event risk so a driver can choose the lower-risk
> corridor.

Point at:

- national outlook
- active hazards
- highest-risk monitored points

### 0:20-0:55 - Route Risk Comparison

Open `/map?origin=Seattle&destination=Miami%20Beach`.

Show:

- Fastest vs. lower-weather-risk vs. balanced alternatives.
- Segment risk strip.
- Risk inspector showing route checkpoints, NWS/weather source status, and
  selected-route risk score.

Say:

> The route engine keeps the familiar map UX, but adds a risk-adjusted cost
> model. The backend can also join configured WZDx/511 road events into edge
> traffic risk for VRP and multi-stop planning.

### 0:55-1:20 - Alert Intelligence

Open `/alerts?q=flood`.

Show:

- live alert search
- hazard categories
- route-impact language

Say:

> Alerts are searchable operational objects, not static text. The UI is designed
> for questions like "what flood warnings affect my route or city?"

### 1:20-1:40 - SaaS Boundary

Open `/saved`, then `/usage`.

Show:

- unauthenticated saved-data boundary, or saved routes if logged in
- plan/capacity/usage meters

Say:

> Private watchlists sit behind Cognito/JWT. The Spring Boot platform API derives
> user scope server-side, enforces saved-route quotas, and supports
> Idempotency-Key so client retries do not duplicate saved routes.

### 1:40-2:00 - Operations Evidence

Open `/status`, then point to the README verification table.

Say:

> I treated this like a small production release: CloudFront/S3/API
> Gateway/Lambda/DynamoDB through Terraform, GitHub Actions OIDC deploys,
> runtime status, local stress evidence, rollback docs, and test gates across
> React, Spring Boot, and FastAPI.

If asked for the GitHub proof, show:

- `README.md` 30-second version
- `docs/architecture/`
- `docs/ops/cloud-load-test-strategy.md`
- latest GitHub Actions Deploy Dev run

## Local Demo

Use local mode when iterating or interviewing from the development machine.

```powershell
npm install --prefix web
pip install -r services/api/requirements.txt
Push-Location services/api
python -m pytest tests -q
Pop-Location
npm run lint --prefix web
npm run build --prefix web
```

Start the app using the repository scripts or the existing development
terminal. The important browser path is:

- `http://127.0.0.1:5173/`
- `/map`
- `/dashboard`
- `/alerts`
- `/saved`
- `/status`

## ML Served-Cost Demo

This command is fully local. It does not call AWS, Google, NWS, Open-Meteo,
OSRM, or any paid provider.

```powershell
cd services/api
python scripts/run_vrp_served_cost_demo.py `
  --artifact-dir ..\..\tmp\demo-vrp-ml `
  --model-version demo-served-delay-v1
```

What it demonstrates:

- saved-route-style observations become delay-training examples;
- scikit-learn Ridge training is compared against a mean-delay baseline;
- a shadow artifact is written with `served_to_users=false`;
- a promoted artifact is written with `served_to_users=true`;
- OR-Tools solves the same route scenario with and without ML served cost;
- the served path records `ml_served_delay_applied` in edge explanations.

Expected signal:

```json
{
  "demo": "vrp-ml-served-cost",
  "releaseGatePassed": true,
  "baseline": {"status": "FEASIBLE", "routeCount": 1},
  "servedMl": {
    "status": "FEASIBLE",
    "sourceStatus": {"ml_cost_model": "SERVING_REQUESTED:demo-served-delay-v1"}
  },
  "savannahEdge": {
    "primaryHazard": "Flash Flood Warning",
    "mlDelaySeconds": 3080.7
  }
}
```

The exact objective value can change if solver or model constants change. The
contract is that the route remains feasible, no jobs are dropped, a promoted
model is reported, and the high-risk edge explains the applied ML delay.

## Cloud Preview Demo

Use the CloudFront preview for public-facing portfolio review:

- Web: `https://d23c97ytqgl4xu.cloudfront.net/`
- Health: `https://d23c97ytqgl4xu.cloudfront.net/api/health`

Show these pages:

1. Home: product framing and live alert summary.
2. Map: route alternatives and segment-level risk explanation.
3. Dashboard: national monitor and active hazard summary.
4. Alerts: searchable live alert list by hazard, city, county, corridor, or
   route impact term.
5. Saved: private saved routes and saved places after login.
6. Status: source health, frontend runtime telemetry, and performance snapshot.

## Post-Deploy Smoke Check

After a `main` deploy, verify:

```powershell
$base = "https://d23c97ytqgl4xu.cloudfront.net"
Invoke-RestMethod "$base/api/health"
Invoke-RestMethod "$base/api/risk/weather-raster" | Select-Object generated_at, expires_at, url
Invoke-WebRequest "$base/" -UseBasicParsing | Select-Object StatusCode
```

Then open `/status`. When the deploy workflow has set `VITE_GIT_SHA`, the Build
mode row should show the deployed commit SHA.

Known-good dev deploy before this stabilization pass:

- SHA: `61a3d97d3cf3b0563a2eb8bfdb9475a27c0cb7e1`
- Workflow: `Deploy Dev`
- Status: success
- Run: https://github.com/signalgap9-del/peachtree-dispatch/actions/runs/28837589265

## Security Demo Talking Points

- Cognito JWT protects private `/me/**` data.
- The Platform API derives owner keys from the authenticated subject instead of
  trusting user-supplied owners.
- DynamoDB writes/deletes use owner-scoped keys and conditional checks.
- CloudFront injects an origin verification header for `/api/*`.
- Outbound provider calls are allowlisted, must use HTTPS, block redirects, and
  reject metadata/local/private-network targets by default.
- ML artifacts are JSON coefficient artifacts, not pickle/joblib objects.
- Local stress tests are cost-free; bounded cloud load tests are documented but
  not run against the preview by default.

## What Not To Claim Yet

- Do not claim full commercial launch readiness.
- Do not claim nationwide high-resolution HRRR/MRMS raster cadence is running
  continuously.
- Do not claim Google OAuth is verified in deployed production until the real
  client ID/secret is wired and smoke-tested.
- Do not claim the ML model is trained on large real-world field observations
  yet. The workflow is real; the demo data is fixture data.
