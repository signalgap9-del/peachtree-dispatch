# Production Risk Routing

## Current-State Audit

| Capability | Before this change | Production truth |
| --- | --- | --- |
| Place search | Live Nominatim U.S. search | Live Nominatim with Open-Meteo city fallback, but public endpoints need a provider contract or self-hosting before commercial traffic |
| Directions geometry | Live OSRM public demo server, first route only | Live road geometry, but no SLA and not suitable as the production provider |
| Alternative route cards | Synthetic time, distance, and risk changes over one geometry | Removed; alternatives now come from actual OSRM alternative-route responses |
| Weather | Live Open-Meteo at origin and destination; silent fake defaults on failure | Route candidates are sampled; failed samples are marked `UNAVAILABLE`, never replaced with plausible fake weather |
| Alerts | Live NWS national and point alerts | Live official U.S. alerts with hazard-category normalization and explicit provider availability status |
| Location score | Heuristic using point weather and active alerts | Still a heuristic and must be calibrated and versioned |
| Route score | Average of origin and destination weather | Candidate geometry is sampled along the route and combined with NWS alerts |
| VRP risk cost | Destination-city weather penalty | Not production-ready; must consume a cached risk-adjusted travel matrix |
| FEMA | UI text and planned architecture only | Not yet ingested; do not present generated FEMA values as live data |

## Hazard Taxonomy

Live NWS alert events are normalized into:

- `TORNADO`
- `TROPICAL_CYCLONE`
- `FLOOD`
- `THUNDERSTORM`
- `WINTER`
- `WILDFIRE`
- `EXTREME_HEAT`
- `WIND`
- `COASTAL`
- `VISIBILITY`
- `OTHER`

Weather and hazard evidence must remain separate. A high precipitation
probability is not itself a flood warning, and FEMA long-term risk is not a live
hazard.

## Versioned Risk Model

Every score must include:

- `modelVersion`
- source timestamps and freshness
- source availability
- hazard contributions
- sampled route locations and arrival-time assumptions
- confidence and missing-data penalties

The initial route score uses the maximum active-alert severity and the mean
sampled weather exposure. This is an explainable baseline, not a scientifically
validated safety guarantee.

## Short And Long Routes

The routing contract accepts coordinates, not state or city boundaries.
Therefore the same implementation supports:

- Miami Beach to West Palm Beach
- Atlanta to Marietta
- Seattle to Miami

Sampling density should later become distance- and duration-aware. Short urban
routes need denser sampling around flood-prone road segments, while interstate
routes need forecast-time alignment across many hours.

## Production VRP Design

OR-Tools must receive a precomputed matrix rather than call routing and weather
providers inside its cost callback.

```text
Stops
  -> routing-provider matrix/paths
  -> route-segment sampler
  -> weather + NWS + static hazard enrichment
  -> TTL risk-adjusted travel matrix
  -> OR-Tools objective
  -> selected tours + explanation
```

Suggested objective:

```text
generalized_cost =
  travel_time_seconds
  + risk_weight * expected_exposure_seconds
  + severe_hazard_penalty
  + lateness_penalty
  + vehicle_restriction_penalty
```

The matrix belongs in an asynchronous optimization job with caching, provider
rate limits, retries, provenance, and a reproducible model version.

The OR-Tools solver now accepts a precomputed `risk_adjusted_matrix`. The
existing synchronous network endpoint still uses its legacy destination-weather
cost until the asynchronous matrix builder and cache are implemented; this
boundary is intentionally explicit.

## Service Boundary

- **Spring Boot platform API:** authentication, users, saved items, alerts,
  request orchestration, DynamoDB access, and public API contract.
- **Python risk engine:** provider adapters, hazard normalization, candidate
  route scoring, risk-adjusted matrix generation, and OR-Tools optimization.

The web client should call only the Spring Boot API in deployed environments.
