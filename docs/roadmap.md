# AtmosPath Roadmap

## Phase 1: Public Read Experience

- Complete map, dashboard, saved, and alert interactions.
- Provide nationwide place search, route alternatives, and honest source status.
- Render national weather/hazard layers and explain route risk.
- Keep CloudFront/API protections and low-cost concurrency caps.

## Phase 2: Data and Risk Integrity

- Build scheduled HRRR/MRMS ingestion and raster-generation jobs.
- Store large artifacts in S3 and current snapshot pointers in DynamoDB.
- Version the risk model and persist explainable risk exposures.
- Add freshness, coverage, confidence, retry, DLQ, and replay controls.

## Phase 3: Authenticated Product

- Add Cognito sign-in and verified JWT ownership.
- Enable saved places, routes, corridors, collections, and route history.
- Enable alert subscriptions and notification deduplication.
- Separate PostGIS migration and runtime credentials before Aurora enablement.

## Phase 4: Production Operations

- Add synthetic checks, SLOs, dashboards, alarms, and incident exercises.
- Test backup/restore and queue replay.
- Review IAM least privilege, dependency risk, and cost anomaly controls.
- Promote through approval-gated production state.

## Phase 5: Portfolio Evidence

- Publish architecture and ERD diagrams.
- Record a short product and incident-recovery demo.
- Show GitHub Actions OIDC, Terraform plans, test evidence, and cost decisions.
- Document measured risk-pipeline latency, cache hit rate, and monthly cost.

## Explicitly Excluded

- Delivery dispatch, fleet CRM, and driver assignment
- Always-on Kubernetes
- Unbounded nationwide compute without freshness and cost controls
