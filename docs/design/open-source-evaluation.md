# Open-Source Evaluation

## Selection Criteria

Candidates are evaluated for license clarity, maintenance state, direct fit,
cross-platform value, and whether adapting them demonstrates stronger
engineering judgment than building the same capability from scratch.

No third-party source code has been imported as part of this research phase.

## Recommended Candidates

| Candidate | License / status | Best use | Recommendation |
| --- | --- | --- | --- |
| [GraphHopper Maps](https://github.com/graphhopper/graphhopper-maps) | Apache-2.0; public and maintained | Address autocomplete, route entry, route alternatives, route detail UX, mobile-browser behavior | Primary route-UX implementation reference; evaluate a scoped adaptation |
| [GraphHopper Maps Capacitor](https://github.com/boldtrn/graphhopper-maps-capacitor) | Verify before import; public | Example of packaging GraphHopper Maps for mobile | Architecture reference only until license and freshness are confirmed |
| [Open-Meteo Weather Map Layer](https://github.com/open-meteo/weather-map-layer) | Verify package/repository license before import; public and active | Continuous weather layers using a MapLibre protocol and forecast variables | Primary weather-layer proof of concept |
| [MapLibre GL JS](https://github.com/maplibre/maplibre-gl-js) | BSD-3-Clause; mature | Web map rendering, layers, camera, controls | Keep as the web map renderer |
| [MapLibre React Native](https://github.com/maplibre/maplibre-react-native) | Verify current license before import; public and active | Native iOS/Android map rendering | Primary native mobile renderer candidate |
| [Ferrostar](https://github.com/stadiamaps/ferrostar) | Verify current license before import; public and active | Cross-platform turn-by-turn navigation with MapLibre | Evaluate for native navigation rather than building turn-by-turn from scratch |
| [Valhalla](https://github.com/valhalla/valhalla) | MIT; mature | Alternative routes, costing, matrices, isochrones, map matching | Strong self-hosted routing-engine candidate |
| [openrouteservice](https://github.com/GIScience/openrouteservice) | Verify current license and service constraints | Customizable routing API and GraphHopper-derived engine | Secondary routing candidate |
| [awesome-maplibre](https://github.com/maplibre/awesome-maplibre) | Curated index | Discover maintained MapLibre ecosystem components | Use for discovery, not product code |
| [Lokus](https://github.com/lokus-ai/lokus) | Fair Core License 1.0; public | Product completeness reference: docs, changelog, tests, local-first positioning, keyboard-first UX, feature surface | Reference only. Do not import code because the license is not a simple permissive fit for this portfolio app |

## Adaptation Boundaries

### Adapt

- Route form behavior and route alternative presentation from GraphHopper Maps.
- Continuous MapLibre weather-layer protocol and variable/timeline patterns from
  Open-Meteo.
- Native map and navigation architecture from MapLibre React Native and
  Ferrostar.
- Routing capabilities such as alternative costing, matrices, and map matching
  from Valhalla or GraphHopper.
- Product-readiness presentation from Lokus: clear README structure, visible
  roadmap, release notes, privacy/security posture, and rich but organized
  feature surfaces.

### Build For This Product

- Composite weather-risk scoring and explanations.
- Risk-aware route ranking: Fastest, Lower Risk, and Balanced.
- Segment exposure calculations and departure-time comparison.
- Saved-place and saved-route monitoring dashboard.
- Cross-source normalization for NWS, weather forecasts, and long-term risk.

### Do Not Import

- Google Maps, Waze, Windy, or FEMA branding and proprietary visual assets.
- Clone repositories with unclear licenses or embedded third-party API keys.
- Proprietary map tiles or scraped community hazard data.
- Dependencies that materially increase recurring AWS cost without a measured
  need.
- Fair-source or commercial-license code from projects such as Lokus unless a
  future legal/licensing review explicitly approves it.

## Proposed Evaluation Spikes

Before selecting the final route stack, run three small, measurable spikes:

1. Render Open-Meteo weather layers over the existing MapLibre map and measure
   initial load, interaction frame rate, and mobile memory.
2. Compare GraphHopper and Valhalla route alternatives for representative
   cross-city and interstate trips, including self-hosting cost.
3. Build one Expo screen with MapLibre React Native and test whether Ferrostar
   can consume the chosen routing output.

The spikes should remain separate from production deployment and should not
create recurring AWS resources.
