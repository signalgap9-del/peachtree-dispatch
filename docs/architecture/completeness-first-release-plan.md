# Completeness-First Release Plan

Date: 2026-07-04

## Decision

Prioritize product completeness over additional platform expansion. GraphQL is deferred until the REST-first app feels coherent, testable, and deployable. The ML workflow should continue, but only as shadow-mode training, backtesting, artifacts, and label collection until product flows are stable.

This does not mean the existing GraphQL scaffold must be deleted immediately. It means GraphQL is no longer on the critical path. ML workflow work remains valuable when it improves route-risk evidence, saved-route observations, and shadow evaluation without taking over served route cost.

## Current Focus

AtmosPath should first prove these user-visible flows:

1. Search a city, highway, or hazard without being bounced to an unrelated page.
2. See official alert/weather/roadwork data with honest freshness and provider status.
3. Plan a route and compare alternatives with a clear risk explanation.
4. Save a route, revisit it, see current risk/history, and delete or update it.
5. Sign in with Google/Cognito and verify saved data persists in the deployed app.
6. Run smoke/performance checks without accidentally generating cloud spend.

## P0 Completion Tasks

### Task 1: Alert Command Center Works In Place

**Description:** `/alerts` owns alert search, filtering, map focus, and selected alert details. Dashboard/Home should link into alerts rather than duplicating search boxes.

**Acceptance criteria:**
- Search for `flood`, `heat`, `Miami`, or a state/city term filters the list without navigating away.
- Selecting an alert focuses the map/details panel on that alert or its affected area.
- Empty and degraded states explain whether data is unavailable, stale, or simply has no matching alerts.

**Verification:**
- Playwright alert search/filter test.
- Browser manual check at desktop and mobile widths.

### Task 2: Saved Routes Are First-Class

**Description:** Saved routes must be the persistent product loop, not a localStorage side path.

**Acceptance criteria:**
- Save current route with name, origin, destination, vehicle type, usual departure time, threshold, and monitor flag.
- `/saved` lists saved routes from the backend when authenticated and shows a clear login prompt when anonymous.
- Route detail shows current risk, last checked time, active hazards, risk history, and observation/report controls.

**Verification:**
- Spring controller/repository tests.
- Playwright saved-route contract test.
- Manual deployed smoke after Cognito Google OAuth secrets are wired.

### Task 3: Route Risk Uses Real Provider Signals

**Description:** Route risk should be based on NWS alerts, weather snapshot/raster samples, and road-event provider signals when available.

**Acceptance criteria:**
- Risk response includes model version, source coverage, freshness, and degraded/unavailable reasons.
- Route segments expose the primary hazard and evidence instead of opaque scores.
- Roadwork/WZDx provider metadata appears beside weather alerts where route overlap exists or where state-level data is available.

**Verification:**
- Python route-risk tests with NWS/WZDx fixture data.
- E2E route alternatives test checks evidence labels and no fake/sample copy.

### Task 4: Deployed Auth Smoke Is Boring

**Description:** Google login must stop being a moving target before adding more platform layers.

**Acceptance criteria:**
- Google OAuth client ID/secret are connected through GitHub environment secrets and Terraform variables.
- Deployed Hosted UI redirects back without Bad Request.
- Authenticated API calls include JWT and saved records persist after refresh.

**Verification:**
- Manual deployed smoke evidence.
- GitHub Actions deploy summary links the environment and commit.

### Task 5: Release Evidence Is Repeatable

**Description:** Every public/demo claim needs a repeatable check.

**Acceptance criteria:**
- `npm run validate --prefix perf` passes.
- k6 smoke can be run locally without AWS cost.
- Build/lint/e2e/Python/Spring checks pass for the release branch.
- README screenshots are captured from the deployed app, not generated mockups.

**Verification:**
- PR checklist with command output.
- Updated screenshots in `docs/screenshots/`.

## P1 After P0

- HRRR/MRMS one-shot worker run with measured duration, memory, S3 artifact size, and cost estimate.
- Route observation UI for ML labels.
- Synthetic monitoring for `/`, `/map`, `/api/health`, and one route-planning request.
- Broader external data ingestion: 511 incidents, flood gauges, wildfire/smoke, and state emergency declarations.

## Deferred

- GraphQL BFF.
- MLflow/XGBoost/scikit production dependency stack.
- Kubernetes, always-on Redis, Aurora-by-default.

## Branch/PR Discipline

Do not merge a giant mixed PR containing WZDx, VRP, ML, GraphQL, perf, DB migrations, and frontend redesign together. Prefer coherent slices:

- road events/provider status
- REST route-engine foundation
- saved route monitor
- alert command center
- auth deployed smoke
- performance harness

Each slice should have tests, docs, and a clear reason it improves the product the user can actually see.
