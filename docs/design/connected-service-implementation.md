# Connected Service Implementation

## Implemented Routes

| Route | Status | Data behavior |
| --- | --- | --- |
| `/` | Implemented | Product home with national outlook, quick actions, next trip, and recent items |
| `/map` | Implemented | Existing MapLibre map, nationwide NWS risk, place search, directions, and route comparison |
| `/directions` | Implemented | Opens the same route-comparison workspace as `/map` |
| `/dashboard` | Implemented | Personalized risk matrix, changes, departures, forecasts, and alert summary |
| `/saved` | Implemented | Filterable saved-item grid, collections, contextual inspector, and deep links |
| `/alerts` | Implemented | Prioritized alerts, impact inspector, evidence, and route-alternative action |
| `/locations/miami` | Implemented | Place risk explanation, forecast, alerts, common routes, and long-term context |

## Navigation Contract

The application uses URL-addressable client-side routes and the browser history
API. Global navigation and card actions update the URL and preserve back/forward
behavior. Direct routes are supported by the Vite development fallback and need
the production CDN SPA fallback to continue serving `index.html`.

## Data Boundary

- The Map page continues to use the live place-search, directions, national-risk,
  and location-risk APIs.
- Alerts and place detail use live API results when available and render stable
  demonstration fallbacks if an upstream public weather service is unavailable.
- Home, Dashboard, and Saved currently use explicit demonstration data so the
  complete connected workflow remains reviewable without user accounts or
  persistent saved-item APIs.

## Visual QA

Verified in the in-app browser:

- Desktop Home, Dashboard, Saved, Alerts, Place Detail, and Map.
- Global navigation from Map to Dashboard.
- Mobile Home with bottom navigation.
- Mobile Map with a bottom-sheet route planner.
- No browser console errors during the connected-screen review.

## Remaining Production Work

- Persist saved places, routes, collections, notification preferences, and
  dashboard changes in backend APIs.
- Add authentication and user-scoped data ownership.
- Add real alternative route geometries and segment-level weather exposure.
- Add CDN SPA fallback configuration for direct route requests.
- Add browser-level interaction tests for all navigation contracts.

