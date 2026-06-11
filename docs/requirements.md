# Product Requirements

## Problem

Drivers and travelers across the United States need to understand how current
and forecast weather hazards affect a trip, not just whether a road is fast.
AtmosPath compares road-route alternatives, explains their hazard exposure, and
lets users monitor places, corridors, and routes they care about.

## Users

- **Driver or traveler:** compares safer route alternatives before a trip.
- **Preparedness-focused user:** monitors a city, place, highway, or corridor.
- **Platform operator:** verifies data freshness, risk calculations, and alerts.

## Core User Stories

1. Search for a U.S. place and request routes to another place.
2. Compare multiple alternatives by time, distance, risk, confidence, and hazard exposure.
3. Inspect nationwide weather and severe-hazard layers on a map.
4. Save a place, route, or corridor and organize it into a collection.
5. Subscribe to alerts when a saved item crosses a risk threshold.
6. Review recent route plans and understand why a risk score changed.
7. Continue to receive an honest low-confidence result when a provider is unavailable.

## Scope

### MVP

- Nationwide U.S. place search and point-to-point directions
- Multiple road-route alternatives for short and long trips
- Weather and severe-hazard map layers
- Explainable route and location risk scores
- Saved places, routes, corridors, and collections
- Alert subscriptions after authentication is implemented
- Responsive PWA-style web experience
- Event-driven weather ingestion and risk processing

### Later

- Authenticated user writes through Cognito
- Push and email notifications
- Historical trend analysis over S3/Athena
- Multi-stop personal road-trip optimization
- Native mobile packaging only if product usage justifies it

### Non-Goals

- Delivery dispatch, fleet management, or driver assignment
- Real-time turn-by-turn navigation
- Payments or billing
- Multi-region active-active deployment
- Always-on Kubernetes

## Non-Functional Requirements

| Area | Target |
| --- | --- |
| API availability | 99.9% monthly |
| Cached API latency | p95 under 500 ms |
| Data freshness | Provider and model timestamps visible; stale data clearly labeled |
| Risk integrity | Scores include model version, source status, coverage, and confidence |
| Security | No long-lived AWS credentials; authenticated ownership for user writes |
| Cost | Target under $10/month at portfolio traffic; explicit review before recurring-cost resources |
| Operations | Structured logs, metrics, traces, alarms, DLQ, and replay runbooks |

## Assumed Portfolio Traffic

- Fewer than 100 daily users
- Fewer than 1,000 route calculations per day
- Weather raster refreshes on a bounded schedule
- Bursty traffic with long idle periods
- Most public reads served from CloudFront or short-lived caches
