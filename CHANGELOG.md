# Changelog

All notable AtmosPath changes are tracked here. The project uses semantic
versioning with preview suffixes until the first production launch:

- `v0.1.x`: portfolio preview hardening
- `v0.2.x`: external data and route intelligence expansion
- `v0.3.x`: dispatch optimization and ML workflow expansion
- `v1.0.0`: production launch candidate

## [v0.1.0-preview] - 2026-07-06

### Added

- React/Vite map-first web app with Home, Map, Directions, Dashboard, Saved,
  Alerts, Usage, Pricing, Place Detail, and Operational Status pages.
- MapLibre route-comparison workspace with fastest, lower-weather-risk, and
  balanced alternatives.
- Live hazard and weather-risk UI for national outlook, route segments, saved
  route impacts, and alert search.
- Spring Boot Platform API for authentication boundary, tenant resolution,
  SaaS account summary, quota enforcement, structured API errors, and request
  correlation.
- DynamoDB preview persistence for saved places, saved routes, and daily
  tenant-scoped usage counters.
- Owner-scoped DynamoDB writes/deletes and optional PostGIS RLS migration path.
- Python FastAPI risk engine for place search, route planning, national risk,
  location risk, weather snapshot/raster artifacts, WZDx feed discovery, and
  multi-stop routing.
- OR-Tools-backed VRP foundation with multi-stop route contracts and
  risk-weighted edge-cost modeling.
- ML shadow workflow for saved-route delay examples, offline backtest,
  safe JSON model artifacts, artifact loading, and non-authoritative shadow
  predictions.
- Local API stress harness with recorded result artifact under `docs/ops/`.
- Frontend bundle budget enforcement and runtime status telemetry.
- Cloud load-test design document covering smoke, bounded canary, and optimizer
  endurance levels.
- AWS Terraform deployment path for CloudFront, private S3, API Gateway, Lambda,
  Cognito, DynamoDB, CloudWatch, and GitHub Actions OIDC.

### Verified

- Spring Platform API Maven tests: 27 passed.
- Python Risk Engine pytest suite: 60 passed, 4 warnings.
- Frontend lint, build, design lint, bundle budget, Playwright E2E, and npm
  audit passed.
- Local API stress: 180 requests, concurrency 8, 0 failures.

### Known Boundaries

- Billing collection is intentionally disabled.
- Team/workspace SaaS membership is not part of this phase.
- Google OAuth requires environment secrets to be wired in the deployed
  environment before real social-login smoke testing.
- ML predictions run in shadow mode only and do not choose routes for users.
- Multi-stop optimization is synchronous and should move behind an async job API
  before larger cloud load testing.
- HRRR/MRMS raster ingestion and state 511 incident joins are documented but not
  running at production cadence.
