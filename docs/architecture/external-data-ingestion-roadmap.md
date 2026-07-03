# External Data Ingestion Roadmap

AtmosPath is a route-risk product, not a generic weather dashboard. External data should be ingested only when it improves a driver or dispatcher decision:

- choose a lower-risk route
- delay departure
- watch a saved route
- explain why a route became risky
- avoid showing fabricated or sample-only operational context

This roadmap favors official, free or low-cost sources first. Paid vendor APIs can come later after the portfolio story and risk model are credible.

## Priority 1: Road Incidents, Closures, Construction, And 511

Recommended path:
- Use USDOT WZDx feed registry for work zones and construction feeds where available.
- Add state 511 APIs for high-priority corridors, starting with states that publish documented APIs.
- Store normalized events as `RoadEvent` records and join them against route endpoints, route polyline bounding boxes, and major corridor labels.

Official/reference sources:
- USDOT WZDx: https://www.transportation.gov/av/data/wzdx
- WZDx feed registry: https://data.transportation.gov/Roadways-and-Bridges/Work-Zone-Data-Feed-Registry/69qe-yiui
- WSDOT traveler information API: https://wsdot.wa.gov/traffic/api/
- 511NY developer API: https://511ny.org/developers/doc
- 511WI developer API: https://511wi.gov/developers/doc

Reality check:
- There is no single complete free national traffic incident API.
- State APIs differ in auth, rate limits, fields, and license terms.
- WZDx is strong for construction/work zones, not all crashes or closures.

Implementation slice:
- Start with WZDx registry crawler.
- Add 3 state adapters: WA, NY, WI or GA/FL/TX if public developer APIs are available.
- Cache every 5-15 minutes.
- Risk model impact: `construction_delay`, `closure_blocking`, `incident_severity`, `truck_restriction`.

## Priority 2: Disaster And Emergency Declarations

Recommended path:
- Use OpenFEMA for federally declared disasters and emergency declarations.
- Use it as contextual severity, not second-by-second route blocking.
- Join by state, county/FIPS, incident type, declaration date, and route corridor.

Official sources:
- OpenFEMA API: https://www.fema.gov/about/openfema/api
- OpenFEMA datasets: https://www.fema.gov/about/openfema/data-sets
- Disaster Declarations Summaries v2: https://www.fema.gov/openfema-data-page/disaster-declarations-summaries-v2

Reality check:
- FEMA data is official but not always real-time enough for immediate navigation.
- Best use is "regional context" and watchlist enrichment.

Implementation slice:
- `EmergencyDeclarationAdapter`
- DynamoDB item type `EMERGENCY_DECLARATION`
- API field on route risk: `emergency_context[]`

## Priority 3: Flood, River Gauge, Water Level, Inundation

Recommended path:
- Use USGS Water Services for streamflow/gage height near routes.
- Continue using NWS alerts for flood watches/warnings.
- Later add NOAA/National Water Model or AHPS replacement data if needed.

Official sources:
- USGS Water Services: https://waterservices.usgs.gov/
- USGS Instantaneous Values: https://waterservices.usgs.gov/docs/instantaneous-values/
- USGS modern Water Data APIs: https://api.waterdata.usgs.gov/
- NWS API and alerts: https://www.weather.gov/documentation/services-web-api
- NWS alerts web service: https://www.weather.gov/documentation/services-web-alerts

Reality check:
- USGS is excellent for gauges, but flood impact on roads still needs spatial heuristics.
- NWS alert polygons are often the first production-ready signal for warnings.

Implementation slice:
- Query gauges within route corridor bounding boxes.
- Cache latest gage height/streamflow hourly.
- Add `flood_gauge_score` and `flood_alert_score`.

## Priority 4: Wildfire, Smoke, Air Quality

Recommended path:
- Use NASA FIRMS for active fire detections.
- Use AirNow for AQI observations/forecasts after obtaining a public account/API key.
- Combine with NWS fire weather alerts where relevant.

Official sources:
- NASA FIRMS API: https://firms.modaps.eosdis.nasa.gov/api/
- NASA FIRMS overview: https://firms.modaps.eosdis.nasa.gov/
- AirNow API docs: https://docs.airnowapi.org/
- AirNow web services: https://docs.airnowapi.org/webservices

Reality check:
- NASA FIRMS is strong for fire hotspots, not road closure status.
- AirNow is public but requires an account/key and should not be abused for bulk database building.

Implementation slice:
- `WildfireAdapter` for active fire points in corridor buffer.
- `AirQualityAdapter` for origin/destination and major route checkpoints.
- Add `smoke_visibility_score`, `aqi_score`, `fire_proximity_score`.

## Priority 5: Power And Infrastructure Outage

Recommended path:
- Treat as contextual enrichment, not primary routing.
- Use EIA open data for energy context.
- Avoid scraping utility outage maps unless license and robots terms clearly allow it.

Official/reference sources:
- EIA Open Data API: https://www.eia.gov/opendata/
- DOE-417 form context: https://doe417.pnnl.gov/
- DOE-417 instructions: https://doe417.pnnl.gov/instructions

Reality check:
- Fine-grained live outage data is fragmented by utility and often not available through stable public APIs.
- This is not a P0 route-risk dependency.

Implementation slice:
- Add only if dashboard needs regional resilience context.
- Keep behind a feature flag.

## Priority 6: Ports, Freight, Corridor Status

Recommended path:
- Use BTS/FHWA datasets for freight and port context.
- Use 511/WZDx for operational road status near corridors.
- For true port congestion, expect vendor/partner APIs later.

Official sources:
- BTS Port Performance Freight Statistics Program: https://www.bts.gov/ports
- BTS port data inventory: https://data.bts.gov/Maritime-and-Waterways/Port-Data/5rpz-kgm9
- FHWA Freight Performance Measures: https://ops.fhwa.dot.gov/freight/freight_analysis/perform_meas/index.htm

Reality check:
- BTS/FHWA sources are useful for portfolio architecture and analytics, but not enough for real-time dispatch decisions by themselves.

Implementation slice:
- Add `FreightCorridorProfile` records for saved routes.
- Use it to explain strategic risk, not moment-to-moment rerouting.

## Priority 7: News And Local Events

Recommended path:
- Use GDELT as the first free/open news signal.
- Restrict queries to hazards, closures, floods, fires, power outages, and severe weather along saved routes.
- Do not display raw unverified news as official alerts.

Official/reference sources:
- GDELT data and analysis service: https://www.gdeltproject.org/data.html
- GDELT DOC 2.0 API announcement: https://blog.gdeltproject.org/gdelt-doc-2-0-api-debuts/

Reality check:
- News is noisy and can create false confidence.
- Label it as "media signal" and rank below official alerts.

Implementation slice:
- Add `MediaSignalAdapter`.
- Cache 30-60 minutes.
- Summarize only source/title/time/location, no long article copying.

## Recommended Production Sequence

1. Finish Saved Routes watchlist as a first-class account feature.
2. Add same-origin raster proxy and remove sample-only map cards.
3. Add WZDx registry + 1-3 state 511 adapters for road events.
4. Add USGS gauge enrichment for flood-prone route segments.
5. Add NASA FIRMS + AirNow for wildfire/smoke/air quality.
6. Add OpenFEMA declarations to saved route context.
7. Add GDELT media signals as clearly labeled unofficial context.

## Data Model Additions

```text
ExternalSignal
- signalId
- source
- sourceType: WZDX | STATE_511 | OPENFEMA | USGS_WATER | NASA_FIRMS | AIRNOW | EIA | BTS | GDELT
- category: ROAD_CLOSURE | CONSTRUCTION | INCIDENT | FLOOD | WILDFIRE | SMOKE | AQI | DISASTER | POWER | PORT | NEWS
- severity
- confidence
- title
- description
- geometry
- state
- countyFips
- startsAt
- expiresAt
- fetchedAt
- rawHash

SavedRouteImpact
- savedRouteId
- signalId
- impactScore
- reason
- matchedBy: ENDPOINT | CORRIDOR_BUFFER | STATE | COUNTY | LABEL
- generatedAt
```

## Cost Guardrails

- Prefer scheduled ingestion over per-user live fanout.
- Cache by source and geography, then join cached signals to routes.
- Keep adapters behind feature flags.
- Log source status separately from product fallback states.
- Never show demo/sample external data in production routes unless explicitly labeled as demo mode.
