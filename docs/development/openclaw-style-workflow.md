# OpenClaw-Style Development Workflow

Date: 2026-07-04

This repository should grow as a production-shaped system, not as a pile of UI
experiments. Feature work starts at the contract and provider boundary, then
moves upward into frontend state and UI.

## Standard Feature Flow

```text
Problem statement
  -> design note / ADR when the boundary changes
  -> API or data contract
  -> backend service / provider adapter
  -> persistence or state boundary
  -> frontend API client / hook / controller
  -> UI component
  -> tests and evidence
  -> PR review and merge-risk gate
```

## PR Size Guidance

- Small: 1-5 files, under 300 changed LOC.
- Normal: 5-15 files, under 800 changed LOC.
- Large: 15-30 files, under 1500 changed LOC.
- Spike: 30+ files, mark as `[do not merge]` until split.

Large route-engine or data-provider work can intentionally exceed the small PR
target, but it must include a clear slice boundary and reproducible evidence.

## Non-Negotiables

- API contracts are defined before frontend screens depend on them.
- External data is normalized through provider adapters.
- Provider failures expose `source_status` instead of silently showing fake data.
- UI calls feature API functions, not raw provider endpoints.
- Route/risk logic lives in backend services, not inside page components.
- Tests cover the changed contract boundary.
- PR descriptions include evidence, rollback plan, and remaining risk.

## Route Engine Rule

Do not keep expanding the legacy `services/api/app/optimizer.py`. New
multi-stop, VRP, benchmark, and ML-shadow work belongs under
`services/api/app/vrp/` with solver/provider interfaces.

## Evidence Matrix

| Change Surface | Required Evidence |
| --- | --- |
| API contract | unit/API tests, request/response examples |
| Frontend UI | lint, build, E2E or focused browser proof |
| External provider | timeout/fallback test, no secret exposure |
| Database | migration apply/rollback or schema validation |
| Infrastructure | terraform validate/plan and cost note |
| Auth/user data | authorization tests and negative cases |

