# Connected Service Flow

## Visual Baseline

The approved visual baseline is
`mockups/nationwide-route-compare-desktop.png`. Every desktop screen keeps the
same AtmosPath header, typography, spacing, surface treatment, risk
colors, and navigation state. The Map screen remains the deepest interactive
workspace; other screens help users decide what to inspect there.

## Global Navigation

| Navigation item | Destination | User question |
| --- | --- | --- |
| Home | `/` | What matters today, and where do I want to go? |
| Map | `/map` or `/directions` | What is happening, and which route should I take? |
| Dashboard | `/dashboard` | What changed across the places and routes I monitor? |
| Saved | `/saved` | What places, corridors, and trips have I organized? |
| Alerts | `/alerts` | Which hazards require my attention now? |

The header also contains current local weather, notifications, and account
controls. Selecting a global search result opens a place detail or starts a
route.

## Screen Contracts

### Home

Purpose: begin the most common tasks without behaving like a marketing-only
landing page.

- Nationwide outlook map preview.
- Primary search: city, highway, or route.
- Quick actions: Plan a route, Explore risk, Check a saved place.
- Three high-signal national stories with timing and affected regions.
- Recently viewed and saved-route status.

Connections:

- Search result -> `/locations/:slug`
- Plan a route -> `/directions`
- National outlook card -> `/map`
- Saved route card -> `/routes/:id`
- Hazard story -> `/alerts/:id`

### Map / Directions

Purpose: explore layers and compare routes. The approved desktop route-compare
mockup defines this screen.

Connections:

- Route segment -> `/routes/:id?segment=:segmentId`
- City marker -> `/locations/:slug`
- Save action -> adds item to `/saved`
- Alert marker -> `/alerts/:id`

### Dashboard

Purpose: provide a personalized operational summary without becoming a generic
CRM.

- Greeting and time-sensitive summary.
- Saved-item risk trend strip.
- "What changed" feed.
- Upcoming departures and route recommendations.
- Saved-area forecast matrix.
- Compact national outlook map.

Connections:

- Any changed item -> its route or place detail.
- Departure recommendation -> `/directions` with route and time prefilled.
- Forecast cell -> `/locations/:slug?time=:timestamp`.

### Saved

Purpose: organize reusable monitoring targets.

- Tabs: All, Places, Routes, Corridors.
- Search, sort, and view toggle.
- Visual map-backed cards, never stock photography.
- Each item shows current risk, next material change, and notification state.
- Collection support such as Summer trip or Family.

Connections:

- Place -> `/locations/:slug`
- Route or corridor -> `/routes/:id`
- Compare selected routes -> `/directions`
- Manage alerts -> item-specific notification settings

### Alerts

Purpose: prioritize hazards by user impact rather than show a raw government
alert feed.

- Impact tabs: For you, Along saved routes, Nationwide.
- Severity, hazard type, and timing filters.
- Prioritized alert list with affected saved items.
- Map showing alert polygons and affected corridors.
- Selected-alert evidence and recommended action panel.

Connections:

- Affected route -> `/routes/:id?alert=:alertId`
- Affected place -> `/locations/:slug?alert=:alertId`
- View alternatives -> `/directions?avoidAlert=:alertId`

### Place Detail

Purpose: explain risk for a selected city, region, or highway corridor.

- Current composite risk and confidence.
- 24-hour and 7-day risk timelines.
- Active hazards and source evidence.
- Long-term FEMA context clearly separated from live conditions.
- Common outbound routes and departure-time comparisons.

Connections:

- Plan from here -> `/directions?from=:placeId`
- Save place -> `/saved`
- Active alert -> `/alerts/:id`
- Risk layer -> `/map?place=:placeId&layer=:layerId`

## Core Interaction Loop

1. A user sees a meaningful change on Home, Dashboard, or Alerts.
2. They open the affected place, corridor, or saved route.
3. They understand the contributing hazard, timing, and confidence.
4. They compare route or departure-time alternatives in Map / Directions.
5. They save the decision target and receive future change alerts.

This loop makes the screens one service rather than a set of unrelated
dashboards.
