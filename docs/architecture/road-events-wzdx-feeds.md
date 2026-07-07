# Road Events and WZDx Feed Discovery

Date: 2026-07-07

## Objective

AtmosPath needs road-condition data in addition to weather alerts. The first
road-events slice adds a small, independently testable API boundary around the
USDOT Work Zone Data Exchange feed registry and a bounded route-edge join for
configured WZDx/511 GeoJSON feeds.

This is not the full scheduled incident ingestion pipeline yet. It is the
discovery layer plus a runtime adapter that can read selected no-key GeoJSON
feeds, normalize nearby events, and add them to edge-level route risk.

## Current Slice

- `GET /road-events/feeds`
- Optional `state` filtering
- Bounded `limit` parameter
- `RoadEventFeed` and `RoadEventFeedRegistry` response models
- FastAPI and internal-handler support
- Provider tests for active-feed parsing, API-key detection, and filtering
- `VRP_ROAD_EVENT_FEED_URLS` for comma-separated WZDx/511 GeoJSON feed URLs
- `RoadEventEdgeRiskProvider` for joining closure/work-zone/crash events to
  route edges by corridor distance
- Tests for closure/work-zone normalization, secret-free source handling, and
  risk scoring near a route edge

## Source

- Default registry URL: `https://data.transportation.gov/resource/69qe-yiui.json`
- Override with `WZDX_REGISTRY_URL`
- Runtime edge joins are disabled unless `VRP_ROAD_EVENT_FEED_URLS` is set.
- Feed polling is in-memory cached with `VRP_ROAD_EVENT_CACHE_TTL_SECONDS`
  (default 300 seconds).
- Source status is marked `LIVE` only when the registry response is fetched and
  parsed successfully.

## Product Rules

- The registry API returns feed metadata, not fake incident events.
- Feeds that require keys are exposed as metadata but not treated as immediately
  usable live road-event sources.
- Edge-risk joins use configured feeds only; they do not silently scrape every
  national feed.
- Query strings are removed from event source labels so API keys are not echoed
  in risk explanations or tests.
- Future ingestion should be state/feed scoped to control cost and avoid polling
  every national feed unnecessarily.

## Next Slices

1. Add UI search/filter for roadwork and closure feeds.
2. Add a selected-feed ingestion worker for no-key GeoJSON feeds.
3. Add event age/freshness decay and route-direction matching.
4. Add 1-3 state-specific 511 adapters for feeds that are not WZDx GeoJSON.
