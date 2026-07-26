# 23. Routing provider abstraction (OSRM / Google Routes) and MapTiler tiles

Date: 2026-07-27

## Status

Accepted

## Context

Routing was hard-wired to the public OSRM server in three places:
`app/directions.py` (route alternatives), `app/vrp/matrix.py` (OSRM table
matrix), and `app/vrp/geometry.py` (single-leg geometry). OSRM is free and
keyless, which is ideal for local development, but production needs
traffic-aware routing quality. The Google Routes API provides that, and the
base map tiles (previously CARTO-hosted OSM rasters) move to MapTiler to
align with the same provider strategy.

## Decision

1. Introduce `services/api/app/routing/` with a provider-neutral contract:
   - `RoutingProvider` protocol: `route(waypoints, *, alternatives)` and
     `distance_matrix(waypoints)`.
   - Common `Route` (coordinates, distance_meters, duration_seconds,
     optional static/free-flow duration) and `Matrix` dataclasses.
   - `OsrmProvider` (default, keyless) and `GoogleRoutesProvider`
     (X-Goog-Api-Key header auth, X-Goog-FieldMask, TRAFFIC_AWARE_OPTIMAL).
   - `get_routing_provider()` factory driven by `ROUTING_PROVIDER` and
     `GOOGLE_ROUTES_API_KEY`. Default is OSRM; Google is selected when
     requested or when a key is present; a missing key with
     `ROUTING_PROVIDER=google` logs a warning and degrades to OSRM.
2. `directions.py`, `vrp/matrix.py`, and `vrp/geometry.py` consume the
   factory. VRP keeps pydantic adapters (`OsrmTableMatrixProvider`,
   `RoutingProviderMatrixAdapter`, geometry equivalents) so solver contracts
   and provider labels (`osrm-table`, `google-routes`) stay stable, and the
   haversine/estimated fallbacks still guarantee a matrix/leg when the live
   provider fails.
3. Google Routes calls go through the existing SSRF-safe `outbound_http`
   wrapper; `routes.googleapis.com` and `api.maptiler.com` are added to the
   allowlist.
4. Frontend keeps MapLibre GL. A shared `web/src/mapStyle.ts` selects the
   MapTiler streets-v2 style when `VITE_MAPTILER_API_KEY` is set and falls
   back to keyless OpenStreetMap raster tiles otherwise.

## Consequences

- The app runs with zero keys in local development (OSRM + OSM tiles).
- Google Routes is US-only; this matches the product's US-only scope and is
  documented in `google_routes.py`.
- OSRM wire behavior is preserved (same endpoints, query parameters, and
  response parsing), so existing OSRM-path behavior is unchanged.
- `app/network.py` still calls OSRM directly for the network overview; it
  was out of scope for this change and can adopt the factory later.
- All keys are environment variables; no key material is committed.
