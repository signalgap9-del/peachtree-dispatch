# Road Events and WZDx Feed Discovery

Date: 2026-07-04

## Objective

AtmosPath needs road-condition data in addition to weather alerts. The first
road-events slice adds a small, independently testable API boundary around the
USDOT Work Zone Data Exchange feed registry.

This is not the full incident ingestion pipeline yet. It is the discovery layer
that tells the product which state DOT feeds are active, which feeds require API
keys, and where future roadwork/closure/event ingestion should connect.

## Current Slice

- `GET /road-events/feeds`
- Optional `state` filtering
- Bounded `limit` parameter
- `RoadEventFeed` and `RoadEventFeedRegistry` response models
- FastAPI and internal-handler support
- Provider tests for active-feed parsing, API-key detection, and filtering

## Source

- Default registry URL: `https://data.transportation.gov/resource/69qe-yiui.json`
- Override with `WZDX_REGISTRY_URL`
- Source status is marked `LIVE` only when the registry response is fetched and
  parsed successfully.

## Product Rules

- The API returns feed metadata, not fake incident events.
- Feeds that require keys are exposed as metadata but not treated as immediately
  usable live road-event sources.
- Future ingestion should be state/feed scoped to control cost and avoid polling
  every national feed unnecessarily.

## Next Slices

1. Add UI search/filter for roadwork and closure feeds.
2. Add a selected-feed ingestion worker for no-key GeoJSON feeds.
3. Normalize roadwork, lane closure, crash, and construction events into one
   route-risk event model.
4. Feed route corridor intersections into `EdgeRiskProvider`.
