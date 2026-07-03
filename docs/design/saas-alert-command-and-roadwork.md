# SaaS Alert Command and Roadwork Data Direction

Date: 2026-07-04

## Why this change exists

The dashboard previously showed a search-like control that immediately navigated
to `/alerts` on focus. That violated the product expectation set by the UI: a
search field should accept input, narrow nearby content, and make the resulting
map state obvious before asking the user to leave the page.

This slice turns the dashboard alert area into a small command surface:

- type `flood`, `heat`, `Miami`, `I-95`, or a county-like term inline
- filter by hazard category without page navigation
- show official NWS alert matches and nearby monitored weather signals
- navigate to the full alert center only on explicit result/open actions
- focus the alert map on the selected/filtered alert instead of leaving the
  national map unrelated to the user's search

## References checked

| Reference | License / source status | Pattern adopted |
| --- | --- | --- |
| Radix UI Primitives docs, https://www.radix-ui.com/primitives/docs/overview/introduction | MIT, maintained by WorkOS; official docs emphasize accessible, customizable primitives. | Use as the direction for future dialog, popover, tooltip, select, and command primitives instead of hand-rolled focus management. No Radix code imported in this slice. |
| shadcn/ui repo, https://github.com/shadcn-ui/ui | MIT; component source is intended to be customized and owned by the app. | Adopt the "command/card/list first, page navigation second" dashboard pattern. No source copied because this project is not on Tailwind yet. |
| MapLibre GL JS fitBounds example, https://www.maplibre.org/maplibre-gl-js/docs/examples/fit-a-map-to-a-bounding-box/ | BSD-3-Clause project docs/examples. | Use `fitBounds`-style viewport changes when filtered alert/weather data changes. |
| React Router `useSearchParams`, https://reactrouter.com/api/hooks/useSearchParams | Official docs. | Keep alert center query/category/severity shareable in URL state. Dashboard stays local until the user explicitly opens the full alert center. |
| FHWA WZDx, https://ops.fhwa.dot.gov/wz/wzdx/index.htm | Official FHWA data standard. | Roadwork/closure data should be modeled as a separate external provider layer, not mixed into air-quality or generic news filler. |
| USDOT WZDx overview, https://www.transportation.gov/av/data/wzdx | Official USDOT overview. | WZDx is the first target for road work, detours, restrictions, and temporary road events. |

## Decisions

1. Do not add a new visual framework in the same PR as behavior fixes.
   Radix/shadcn are the right direction, but the safer path is to fix the broken
   search/map contract first, then migrate primitives incrementally.

2. Keep React Router URL search state on the full Alerts page.
   The dashboard command surface uses local state because typing should not
   cause navigation or URL churn. Pressing Enter or clicking a result opens the
   full alert center with URL-backed filters.

3. Do not add GraphQL yet.
   GraphQL becomes useful when the app has multiple composed resources per view:
   saved route + risk history + alert overlaps + roadwork + notification inbox.
   Today the immediate issue is not overfetching; it is product correctness and
   state consistency. When that composition layer is ready, AWS AppSync is the
   AWS-aligned candidate.

4. Treat roadwork/closures as first-class operational risk.
   Air quality is lower priority for this product. The next provider track
   should ingest FHWA WZDx/state 511 feeds, normalize them as road events, and
   expose them beside NWS alerts and route-risk segments.

## Next implementation track

- `RoadEvent` contract:
  - `eventId`
  - `source`
  - `eventType`: `WORK_ZONE`, `LANE_CLOSURE`, `FULL_CLOSURE`, `DETOUR`, `INCIDENT`
  - `severity`
  - `roadName`
  - `area`
  - `geometry`
  - `startTime`
  - `endTime`
  - `description`
  - `lastUpdated`
- API:
  - `GET /api/v1/road-events?bbox=&q=&eventType=`
  - `GET /api/v1/routes/{routeId}/road-events`
- UI:
  - Alert center tab: `Weather` / `Roadwork`
  - Map layer toggle: `Weather risk` / `NWS alerts` / `Roadwork & closures`
  - Saved route detail: `Road impacts on this route`

