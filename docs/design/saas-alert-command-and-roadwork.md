# SaaS Alert Command and Roadwork Data Direction

Date: 2026-07-04

## Why this change exists

The dashboard previously showed a search-like control while the Alerts page also
had a full alert search. That created two competing entry points and made the
product feel stitched together. A production SaaS dashboard should brief the
user and provide clear routes into the deeper workflow; the dedicated alert
center should own search, filtering, and map focus.

This slice makes `/alerts` the single alert command center:

- type `flood`, `heat`, `Miami`, `I-95`, or a county-like term on the Alerts page
- filter by hazard category with URL-backed state
- show official NWS alert matches and nearby monitored weather signals
- focus the alert map on the selected/filtered alert instead of leaving the
  national map unrelated to the user's search
- keep Dashboard as a briefing surface with chips and explicit links into
  Alerts, not a second search UI

## References checked

| Reference | License / source status | Pattern adopted |
| --- | --- | --- |
| Radix UI Primitives docs, https://www.radix-ui.com/primitives/docs/overview/introduction | MIT, maintained by WorkOS; official docs emphasize accessible, customizable primitives. | Use as the direction for future dialog, popover, tooltip, select, and command primitives instead of hand-rolled focus management. No Radix code imported in this slice. |
| shadcn/ui repo, https://github.com/shadcn-ui/ui | MIT; component source is intended to be customized and owned by the app. | Adopt the "command/card/list first, page navigation second" dashboard pattern. No source copied because this project is not on Tailwind yet. |
| MapLibre GL JS fitBounds example, https://www.maplibre.org/maplibre-gl-js/docs/examples/fit-a-map-to-a-bounding-box/ | BSD-3-Clause project docs/examples. | Use `fitBounds`-style viewport changes when filtered alert/weather data changes. |
| React Router `useSearchParams`, https://reactrouter.com/api/hooks/useSearchParams | Official docs. | Keep alert center query/category/severity shareable in URL state. Dashboard stays local until the user explicitly opens the full alert center. |
| FHWA WZDx, https://ops.fhwa.dot.gov/wz/wzdx/index.htm | Official FHWA data standard. | Roadwork/closure data should be modeled as a separate external provider layer, not mixed into air-quality or generic news filler. |
| USDOT WZDx overview, https://www.transportation.gov/av/data/wzdx | Official USDOT overview. | WZDx is the first target for road work, detours, restrictions, and temporary road events. |
| USDOT WZDx Feed Registry, https://data.transportation.gov/Roadways-and-Bridges/Work-Zone-Data-Feed-Registry/69qe-yiui | Official public registry of WZDx feeds. | Add a backend-only registry reader that exposes provider counts and endpoint hosts without leaking raw feed URLs or embedded access tokens. |

## Decisions

1. Do not add a new visual framework in the same PR as behavior fixes.
   Radix/shadcn are the right direction, but the safer path is to fix the broken
   search/map contract first, then migrate primitives incrementally.

2. Keep React Router URL search state on the full Alerts page.
   Dashboard chips and summary rows navigate into `/alerts` with URL-backed
   filters. Search inputs should not be duplicated across pages unless they
   share the same command model and map state.

3. Add GraphQL only as the composition layer.
   GraphQL is now useful because route planning, saved routes, risk history,
   alert overlaps, roadwork, and ML shadow status are becoming one screen-level
   workflow. REST remains the stable operational API; GraphQL composes those
   resources for the frontend. AWS AppSync remains the AWS-aligned managed
   candidate if subscriptions or multi-source resolver composition justify it.

4. Treat roadwork/closures as first-class operational risk.
   Air quality is lower priority for this product. The next provider track
   should ingest FHWA WZDx/state 511 feeds, normalize them as road events, and
   expose them beside NWS alerts and route-risk segments.

5. Batch deploys.
   Small UI/API slices should land on a feature branch with local test evidence.
   Merge/deploy only after a coherent product increment is complete or when an
   urgent production bug requires a hotfix.

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
