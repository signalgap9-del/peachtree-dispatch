# Product UX Research: Nationwide Weather-Aware Navigation

## Research Goal

Define a portfolio-grade, nationwide U.S. navigation product that feels as
immediate as a consumer map application while making current and forecast
weather risk understandable before and during a trip.

This is not a fleet CRM. The primary object is a trip or place, and the primary
canvas is the map.

## Reference Screens

Private research captures are stored under
`tmp/design-research/screenshots/`. They are not production assets and must not
be shipped.

| Product | Screen studied | Pattern to adapt | Do not copy |
| --- | --- | --- | --- |
| Google Maps | Directions from Seattle to Miami | Map-dominant layout, compact directions panel, transport modes, clearly ranked alternative routes | Google branding, proprietary tiles, icons, or exact styling |
| GraphHopper Maps | Seattle to Miami alternatives | Open implementation of compact route entry, profile selection, route alternatives, route geometry, and elevation detail | Product branding or code without preserving Apache-2.0 notices |
| Windy | Nationwide weather map | Continuous weather visualization, layer selector, timeline playback, location summary | Branding, proprietary forecast presentation, or visual assets |
| Waze Live Map | Traffic and hazard map | Driver-first hazard markers placed directly on roads and glanceable incident categories | Waze branding, icons, or community data |
| Open-Meteo Weather Maps | Weather variable map | MapLibre-compatible weather layers, color legend, model/variable controls, forecast timeline | Third-party basemap assets |
| FEMA RAPT | Resilience analysis overview | National overview followed by selected-community analysis and layered evidence | FEMA branding or presenting long-term risk as live conditions |

## Product Principles

1. **Map first.** The map remains the largest and most stable part of the
   interface. Panels explain or control the map; they do not replace it.
2. **Two primary modes.** Explore/Risk answers "what is happening there?" and
   Directions answers "which route should I take?"
3. **Alternatives are the product.** A weather-aware navigation service must
   compare at least Fastest, Lower Risk, and Balanced routes.
4. **Risk must be explainable.** Every score exposes contributing hazards,
   affected route segments, data time, and confidence.
5. **Progressive disclosure.** National conditions, route summaries, segment
   details, and raw alerts appear at different zoom and interaction levels.
6. **Consumer clarity, operational depth.** The default view is calm and
   approachable; advanced weather layers and evidence remain one interaction
   away.

## Recommended Information Architecture

| Route | Purpose | Primary UI |
| --- | --- | --- |
| `/` | Explain the product and begin a place or route search | Landing page with national condition preview |
| `/map` | Explore current and forecast risk anywhere in the U.S. | Full-screen map, layer picker, forecast timeline |
| `/directions` | Compare route alternatives | Directions panel, route cards, selected-route risk inspector |
| `/dashboard` | Monitor saved places and routes | Change feed, risk forecast, saved-area map previews |
| `/locations/:slug` | Understand one place or corridor | Place risk summary, timeline, active alerts, evidence |
| `/routes/:id` | Inspect a saved or shared trip | Segment risk, alternatives, departure-time comparison |
| `/alerts` | Review important changes | Prioritized alert center |
| `/saved` | Manage saved places and routes | Searchable saved collection |
| `/settings` | Control units, notifications, accessibility | Preferences |

Detailed street-address search is not a first-release differentiator. City,
region, highway corridor, and origin/destination search are sufficient for the
initial weather-risk use case.

## Core Desktop Screens

### Explore / Risk

- Full-canvas nationwide map with a continuous weather-risk surface.
- Compact global search in the upper-left.
- Layer control for precipitation, wind, temperature, active alerts, and
  composite trip risk.
- Forecast timeline along the bottom, including play/pause and data timestamp.
- Contextual place inspector appears only after a selection.
- National summary is a small overview, not a permanent dashboard wall.

### Directions / Route Compare

- Left panel contains origin, destination, departure time, and travel mode.
- Show three route alternatives whenever routing data permits.
- Each route card includes duration, distance, major roads, weather risk score,
  risk delta, hazard count, and a plain-language label.
- Selecting a route highlights it and opens a contextual risk inspector.
- The selected route shows risk by segment and expected hazard timing.

Recommended route labels:

- **Fastest**: minimum travel time.
- **Lower weather risk**: materially lower composite risk, even if slower.
- **Balanced**: best weighted tradeoff between time and risk.

### Dashboard

The dashboard is a personalized monitoring surface, not a CRM table.

- Saved cities, corridors, and routes.
- "What changed" feed since the user's last visit.
- Near-term risk forecast for saved items.
- Current nationwide outlook and notable events.
- Small map previews that open directly into Explore or Directions.

## Mobile And PWA Behavior

- Full-screen map is the stable background.
- Search and mode controls remain compact at the top.
- Route alternatives and place risk use a bottom sheet with peek, half, and full
  states.
- Bottom navigation contains Home, Map, Saved, and Alerts.
- Hazard cards prioritize severity, timing, location, and one recommended
  action.
- Offline-ready shell, saved-trip access, installability, and notification
  permissions make the web app a useful PWA.
- Native mobile should share domain models and design tokens, but use a native
  map renderer and navigation SDK instead of embedding the web view.

## Risk Presentation

Risk is useful only when users can understand why it changed.

Every score should support:

- Overall score and category: Low, Moderate, High, Severe.
- Contributing hazards and their weights.
- Forecast window and source timestamp.
- Route miles and estimated minutes exposed to each hazard.
- Confidence or data-availability indicator.
- Comparison against the fastest alternative.

Avoid a single opaque heatmap. The composite layer must be switchable to its
underlying weather and alert layers.

## Architecture Direction

- **Web/PWA:** React with MapLibre GL JS and shared design/domain packages.
- **Native mobile:** React Native/Expo with MapLibre React Native.
- **Navigation UX:** adapt concepts and eligible implementation patterns from
  GraphHopper Maps; evaluate Ferrostar for turn-by-turn navigation.
- **Routing backend:** evaluate GraphHopper and Valhalla for alternatives,
  costing, matrices, and future route-risk weighting.
- **Weather map layers:** evaluate `open-meteo/weather-map-layer` for continuous
  MapLibre weather layers and forecast-time controls.
- **Live hazards:** continue using official NWS alert data; preserve a separate
  boundary between live alerts and long-term FEMA risk.
- **Shared packages:** design tokens, API client, risk-domain types, route
  comparison logic, and analytics events.

## Decision

The next major frontend redesign should start with the Directions / Route
Compare screen and the Explore / Risk screen. They establish the product's
consumer map interaction model; landing and dashboard pages should reuse their
visual language afterward.

