# Alert Intelligence Redesign

Date: 2026-07-03

## Why This Change Exists

The previous dashboard treated live alerts like a text feed and showed a small
decorative national outlook preview. That made the product feel like a demo:
users could not search active hazards, filter by event type, or understand what
raw values such as `rain 92%` and `wind 10` meant for driving.

This redesign makes alerts a first-class product surface:

- Users can search live hazards by city, area, route, or event text.
- Users can filter by category: flood, heat, storm, wind, winter, and fire.
- Dashboard tables translate raw forecast fields into driver-facing labels.
- The weak dashboard mini-map is replaced with an actionable alert intelligence
  panel that routes users into the full alert center.
- Related weather signals are shown next to alert search results so the UI feels
  connected instead of like disconnected sample cards.

## References Checked

1. National Weather Service API
   - URL: https://www.weather.gov/documentation/services-web-api
   - Useful pattern: NWS data is open and cache-friendly, but clients should use
     real headers, rate-limit behavior, and avoid brute-force polling.

2. National Weather Service Alerts Web Service
   - URL: https://www.weather.gov/documentation/services-web-alerts
   - Useful pattern: alerts are structured CAP records with event, area,
     severity, urgency, certainty, effective times, and descriptions. The UI
     should expose those fields as searchable operational data.

3. Google Maps Platform Weather
   - URL: https://mapsplatform.google.com/maps-products/weather/
   - Useful pattern: weather products should combine alerts, current conditions,
     and route safety context instead of displaying isolated weather numbers.

4. React Router URL Search Params
   - URL: https://reactrouter.com/api/hooks/useSearchParams
   - Useful pattern: alert filters belong in the URL so search state is
     shareable, testable, and recoverable after refresh.

## UI Decisions

- Remove the dashboard `National outlook` preview until it can show the real map
  stack. A poor static preview hurts trust more than an intentionally focused
  panel.
- Replace raw `rain` and `wind` values with:
  - `Rain likely`, `Rain possible`, `No rain signal`
  - `light`, `breeze`, `gusty`, `high wind`
  - `Avoid if possible`, `High caution`, `Slow down`, `Normal caution`
- Use URL-backed alert filters:
  - `/alerts?q=Miami`
  - `/alerts?category=flood`
  - `/alerts?severity=severe`
- Keep the dashboard compact, but make every row clickable in meaning: event,
  area, severity, category, and driver action.

## Follow-Up Work

- Replace the dashboard preview with the production MapLibre component only when
  it can reuse the same basemap, alert overlays, and risk layer as `/map`.
- Add backend alert search endpoints for server-side filtering once alert volume
  grows beyond the current national snapshot payload.
- Add event-specific detail pages for flood, heat, storm, fire, winter, and wind
  hazards.
- Add external road-condition feeds after source review: 511/state DOT feeds,
  FEMA disaster declarations, USGS water data, AirNow wildfire/smoke/air quality,
  and EIA outage-related public datasets where license and cost allow it.
