# Incremental Merge Plan

Date: 2026-07-04

## Goal

PR-sized changes must stay reviewable, independently testable, and easy to
explain on a resume. Broad feature branches should be split before they reach
`main`.

## Already Merged

### VRP Route Engine Foundation

- Branch: `yunseopim3/vrp-route-engine-foundation`
- Commit: `8e29628`
- Scope:
  - `services/api/app/vrp/`
  - `POST /routes/multi-stop`
  - `POST /routes/multi-stop/optimize`
  - `POST /vrp/solve`
  - route-engine architecture note
- Verification:
  - `python -m pytest tests/test_vrp_route_engine.py tests/test_api.py tests/test_internal_handler.py -q`
  - `python -m pytest -q`
  - `python -m compileall app`

### WZDx Road-Event Feed Discovery

- Branch: `yunseopim3/wzdx-road-event-feeds`
- Commit: `44ce905`
- Scope:
  - `GET /road-events/feeds`
  - WZDx feed registry provider
  - road-event feed response models
  - road-events architecture note
- Verification:
  - `python -m pytest tests/test_road_events.py tests/test_api.py tests/test_internal_handler.py -q`
  - `python -m pytest -q`
  - `python -m compileall app`

## Remaining Slices

### 1. Saved Route Observations

Scope:

- Spring saved-route observation API
- DynamoDB and RDS repository support
- Flyway migration for route observations
- Java repository and controller tests

Merge gate:

- Java 21 and Maven, or Docker daemon with Maven image, must be available.
- Run `mvn test` from `services/platform-api`.
- Do not merge this slice without Java tests.

### 2. ML Shadow Workflow

Scope:

- Observation-backed delay training dataset
- Artifact-backed model loading
- Shadow-only route delay scoring
- Training script and API smoke tests

Merge gate:

- REST route-engine endpoints stay stable.
- Saved-route observation API is already merged or mocked with an explicit
  boundary.
- Run full API tests and a training smoke command.
- No model output is served as the primary route decision.

### 3. Performance Load-Testing Harness

Scope:

- k6 scenarios and datasets
- README with local/staging usage
- Thresholds for smoke, read mix, route planning, and saved routes

Merge gate:

- Validate the suite structure without hitting production.
- Keep default target local or explicit staging URL only.

### 4. GraphQL Gateway

Scope:

- GraphQL route-engine schema
- GraphQL tests
- Optional frontend client wiring

Merge gate:

- Defer until REST flows are complete and stable.
- GraphQL should wrap existing service boundaries, not introduce alternate
  business logic.

### 5. Frontend Productization

Scope:

- Saved routes/watchlist as a first-class workflow
- Alert search that filters in place instead of navigating unexpectedly
- Roadwork/closure feed discovery UI
- Larger commercial SaaS typography and spacing pass

Merge gate:

- Follow `DESIGN.md`.
- Record references and visual direction under `docs/design/`.
- Run `npm run lint --prefix web`, `npm run build --prefix web`,
  `npm run design:lint --prefix web`, and relevant Playwright tests.

## Merge Rules

- One slice should map to one user-facing or platform capability.
- A slice must include tests for its public contract.
- A slice must include docs when it changes architecture, data sources,
  deployment, or operational behavior.
- Avoid merging UI, ML, GraphQL, infrastructure, and backend persistence in the
  same PR unless the change is explicitly a release integration PR.
