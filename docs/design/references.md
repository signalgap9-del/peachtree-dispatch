# Design References

## Google Maps

- URL: https://www.google.com/maps
- Study: map-dominant canvas, prominent place search, transport-mode switching,
  directions hierarchy, compact floating controls, and immediate route feedback.
- Do not copy: Google trademarks, proprietary map tiles, icons, imagery, or exact
  visual assets.

## Google Labs DESIGN.md

- URL: https://github.com/google-labs-code/design.md
- License: Apache-2.0
- Study: machine-readable design tokens paired with human-readable design intent.
- Applied here: the root `DESIGN.md` and `web` package `design:lint` command.

## Google Map Clone Reference

- URL: https://github.com/Subhampreet/Google-Map-Clone
- License: Apache-2.0
- Study: the familiar search, geolocation, directions, and travel-mode interaction
  model.
- Applied here: interaction concepts only. The repository's Mapbox token and code
  are not copied; Peachtree Routes uses MapLibre, CARTO, OpenStreetMap, and OSRM.

## MapLibre GL JS

- URL: https://github.com/maplibre/maplibre-gl-js
- License: BSD-3-Clause
- Study and use: open-source map rendering, route layers, markers, controls, and
  camera behavior.

## MapLibre Heatmap Example

- URL: https://github.com/maplibre/maplibre-gl-js/blob/main/test/examples/create-a-heatmap-layer.html
- License: BSD-3-Clause
- Adapted: heatmap source/layer structure and zoom-dependent weight, intensity,
  color, and radius expressions for nationwide NWS alert visualization.

## FEMA RAPT And National Risk Index

- URLs:
  - https://www.fema.gov/emergency-managers/practitioners/resilience-analysis-and-planning-tool
  - https://www.fema.gov/flood-maps/products-tools/national-risk-index
- Data: official FEMA open data; National Risk Index v1.20 was released in
  December 2025.
- Study: separate current hazards from long-term community risk, provide a
  national overview, and let users inspect a selected location.
- Applied here: national summary, selected-location risk breakdown, and a future
  boundary for a persistent FEMA long-term-risk layer.

## Esri Drought Tracker

- URL: https://github.com/Esri/drought-tracker
- License: Apache-2.0
- Study: map layer plus compact dashboard, severity legend, and selected-area
  summary pattern.

## NOAA / National Weather Service Alerts

- URLs:
  - https://www.weather.gov/documentation/services-web-api
  - https://www.weather.gov/documentation/services-web-alerts
- Data: official U.S. government open data, free to use with reasonable rate
  limits.
- Applied here: current nationwide watches, warnings, advisories, polygons,
  severity, certainty, urgency, and selected-point alerts.
