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
- Server-backed route decision contract: the risk engine now returns additive `decision` and `segments` fields, and the frontend renders that contract before using its local fallback.
- Route comparison cards: alternatives now show recommended status, risk/time tradeoff against fastest route, coverage, confidence, and hazard categories.
- Route segment strip: selected route weather samples are grouped into live risk segments with hazard severity icons.
- Route-impact alerts: signed-in users can see which saved routes are affected by the selected alert and open the impacted route in directions.
- Live outlook maps: Home, Dashboard, Alerts, and Place outlook now use MapLibre raster basemaps, live weather heat points, and alert markers instead of CSS-only fake map backdrops.
- I18n pass: the header language toggle now renders readable Korean and English copy for the main product pages.
- Google auth readiness: Cognito callback/logout URLs now include deployed CloudFront and local dev origins to avoid callback mismatch Bad Request failures.
- E2E coverage: Playwright now verifies route recommendation UI, segment strip, shareable directions URL, keyboard search, and saved route payload behavior.

## Still Open For A Production-Grade Release

- Replace manual fetch orchestration with TanStack Query or an equivalent request cache when dashboards become user-specific and data-heavy.
- Move route-impact scoring from frontend heuristic into the Spring Boot API when route geometries and alert polygons are persisted together.
- Make dashboard/outlook pages more route-centric: saved corridors, affected routes, alert-to-route matching, and per-user watchlists.
- Split the large stylesheet into route/page/component modules after the interaction model stabilizes.
- Add a mobile map bottom sheet with snap points; the current panel is usable, but not yet native-map quality.
- Finish full i18n coverage for MapPage/ProductPages hardcoded English strings.
- Complete live Google OAuth smoke testing after real client ID/secret are connected.
- Replace remaining decorative saved-card thumbnails with route/place-specific geometry thumbnails.
- Add visual regression tests once screenshots are stable enough to avoid noisy CI failures.
