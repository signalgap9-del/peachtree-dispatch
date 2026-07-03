# Release Cadence

Date: 2026-07-04

## Current Rule

Do not deploy every small UI correction. Work should land in short-lived feature
branches, pass local verification, and merge only when it forms a coherent user
or platform increment.

## Deploy Immediately Only For

- production preview is inaccessible
- auth or route planning is broken for every user
- data contract mismatch blocks the live map or alert center
- a security fix removes credential exposure, broken access control, or unsafe
  external input handling

## Batch Before Deploy

- visual polish that does not block testing
- copy and typography changes
- provider metadata additions
- dashboard summaries and saved-route refinements
- README or architecture documentation

## Standard Flow

1. Create a `yunseopim3/*` feature branch from `main`.
2. Implement a coherent slice with matching API, UI, tests, and docs.
3. Run local verification:
   - `python -m pytest services/api/tests`
   - `npm run lint --prefix web`
   - `npm run design:lint --prefix web`
   - `npm run build --prefix web`
   - `npm run test:e2e --prefix web`
4. Push the branch and open a PR.
5. Merge only after the slice is worth deploying or fixes a production issue.

## Current Product Priority

1. Single alert command center on `/alerts`
2. Real roadwork and closure provider setup through USDOT/FHWA WZDx
3. Saved Routes as a first-class monitor
4. Route-level risk scoring using NWS, weather grid, and road-event overlap
5. Google OAuth production smoke test after secrets are configured
