# Frontend Productization Review

Last updated: 2026-07-03

This document tracks the frontend review items from `codex_frontend_productization_checklist.md` and the research notes shared outside the repository.

## Completed In This Pass

- Real browser routing: replaced manual path state with `react-router-dom` routes for `/`, `/map`, `/directions`, `/dashboard`, `/saved`, `/alerts`, and `/locations/:slug`.
- Shareable directions URL: `/directions?origin=...&destination=...&vehicle=...` now resolves place query params through the platform API and calculates a route when both places resolve.
- Safer API client: platform requests now support typed `ApiError`, request timeout, and `AbortSignal` cancellation.
- Search state UX: map search now exposes loading, no-results, and error states instead of silently hiding failures.
- Keyboard search: place results support Arrow Up, Arrow Down, Enter, and mouse hover highlighting.
- Explicit route refresh: route panel now includes a Calculate route action while keeping automatic calculation for fast demos.
- Decision-first routing: route alternatives now derive a recommendation from route time, risk reduction, data coverage, and confidence.
- Route comparison cards: alternatives now show recommended status, risk/time tradeoff against fastest route, coverage, confidence, and hazard categories.
- Route segment strip: selected route weather samples are grouped into live risk segments with hazard severity icons.
- E2E coverage: Playwright now verifies route recommendation UI, segment strip, shareable directions URL, keyboard search, and saved route payload behavior.

## Still Open For A Production-Grade Release

- Move server-backed route decisioning into the Spring Boot API so the frontend is only rendering a signed/scored recommendation contract.
- Replace manual fetch orchestration with TanStack Query or an equivalent request cache when dashboards become user-specific and data-heavy.
- Make dashboard/outlook pages route-centric: saved corridors, affected routes, alert-to-route matching, and per-user watchlists.
- Add route-impact alerts: alerts should state which saved route, segment, and vehicle profile is affected.
- Split the large stylesheet into route/page/component modules after the interaction model stabilizes.
- Add a mobile map bottom sheet with snap points; the current panel is usable, but not yet native-map quality.
- Finish full i18n coverage for MapPage/ProductPages hardcoded English strings.
- Add visual regression tests once screenshots are stable enough to avoid noisy CI failures.
