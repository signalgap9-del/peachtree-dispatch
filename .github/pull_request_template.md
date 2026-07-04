## What Problem This Solves

<!-- Describe the user/product/operational problem, not just the files changed. -->

## Why This Change Was Made

<!-- Explain the chosen boundary: API contract, service, provider, frontend hook, UI, infra, etc. -->

## User Impact

<!-- What can a user now do, or what user-visible risk is reduced? -->

## Technical Scope

Changed:
- 

Not changed:
- 

## API / Data Contract

New endpoints:
- 

Changed endpoints:
- 

New tables / migrations:
- 

Compatibility notes:
- 

## Architecture / Operational Impact

- [ ] No meaningful recurring cost increase
- [ ] Cost increase documented below
- [ ] External providers have timeout/fallback behavior
- [ ] Degraded/source status is exposed rather than hidden

## Evidence

Commands run:
- [ ] `python -m pytest`
- [ ] `npm run lint`
- [ ] `npm run design:lint`
- [ ] `npm run build`
- [ ] `npm run test:e2e`
- [ ] `mvn --batch-mode verify`
- [ ] `terraform validate`
- [ ] other:

Manual proof:
- 

Screenshots / recordings:
- 

## What Was Not Tested

- 

## Risk Before Merge

- [ ] auth / authorization
- [ ] cost / billing
- [ ] migration / rollback
- [ ] compatibility
- [ ] security
- [ ] provider outage
- [ ] frontend cache
- [ ] geospatial correctness

Details:
- 

## Rollback Plan

- 

## Reviewer Checklist

- [ ] PR has one clear responsibility.
- [ ] API/data contracts are explicit.
- [ ] UI does not own backend business logic.
- [ ] External provider responses are normalized before reaching UI.
- [ ] User-owned data is scoped by authenticated user, not client-supplied owner ID.
- [ ] Failure/degraded mode behavior is documented.
- [ ] Tests cover the changed boundary.
- [ ] Evidence is reproducible.
- [ ] Rollback plan is credible.
