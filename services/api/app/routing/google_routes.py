"""Google Routes API (v2) routing provider.

US-ONLY: this product uses the Google Routes API for United States routing
only. All waypoints are assumed to be inside the US; the provider does not
validate this, and non-US waypoints will produce provider errors or poor
results.

Authentication is an API key sent in the ``X-Goog-Api-Key`` header (never a
query parameter, so it stays out of logs and URLs). ``X-Goog-FieldMask``
keeps responses and billing minimal while still requesting traffic-aware
durations via ``routingPreference=TRAFFIC_AWARE_OPTIMAL``.
"""
from __future__ import annotations

import json
import os
from typing import Any, Sequence
from urllib.error import HTTPError
from urllib.request import Request

from ..outbound_http import safe_urlopen as urlopen
from .provider import Matrix, Route, RoutingProviderError

COMPUTE_ROUTES_URL = "https://routes.googleapis.com/directions/v2:computeRoutes"
COMPUTE_ROUTE_MATRIX_URL = "https://routes.googleapis.com/distanceMatrix/v2:computeRouteMatrix"

ROUTE_FIELD_MASK = ",".join(
    (
        "routes.distanceMeters",
        "routes.duration",
        "routes.staticDuration",
        "routes.polyline.encodedPolyline",
        "routes.routeLabels",
        "routes.routeToken",
    )
)
MATRIX_FIELD_MASK = ",".join(
    ("originIndex", "destinationIndex", "condition", "distanceMeters", "duration")
)


class GoogleRoutesProvider:
    """Routing via the Google Routes API (directions + route matrix)."""

    name = "google-routes"

    def __init__(self, api_key: str | None = None, timeout_seconds: float = 12.0):
        key = (api_key if api_key is not None else os.environ.get("GOOGLE_ROUTES_API_KEY", "")).strip()
        if not key:
            raise ValueError("GoogleRoutesProvider requires an API key (GOOGLE_ROUTES_API_KEY)")
        self.api_key = key
        self.timeout_seconds = timeout_seconds

    def route(
        self,
        waypoints: Sequence[tuple[float, float]],
        *,
        alternatives: bool = False,
    ) -> list[Route]:
        if len(waypoints) < 2:
            raise ValueError("at least two waypoints are required for a route")
        origin, destination = waypoints[0], waypoints[-1]
        intermediates = waypoints[1:-1]
        body: dict[str, Any] = {
            "origin": _waypoint_body(origin),
            "destination": _waypoint_body(destination),
            "travelMode": "DRIVE",
            # Traffic-aware duration; OPTIMAL trades a little latency for
            # higher-quality routing. staticDuration carries the no-traffic
            # baseline alongside it.
            "routingPreference": "TRAFFIC_AWARE_OPTIMAL",
            "computeAlternativeRoutes": alternatives,
            "requestedLanguageCode": "en-US",
            "units": "METRIC",
        }
        if intermediates:
            body["intermediates"] = [_waypoint_body(point) for point in intermediates]

        payload = self._post(COMPUTE_ROUTES_URL, body, ROUTE_FIELD_MASK)
        routes_payload = payload.get("routes")
        if not isinstance(routes_payload, list):
            raise RoutingProviderError("Google Routes response did not contain routes", provider_name=self.name)

        routes: list[Route] = []
        for item in routes_payload:
            polyline = (item.get("polyline") or {}).get("encodedPolyline")
            if not isinstance(polyline, str) or not polyline:
                raise RoutingProviderError(
                    "Google Routes response is missing an encoded polyline", provider_name=self.name
                )
            routes.append(
                Route(
                    coordinates=decode_polyline(polyline),
                    distance_meters=float(item.get("distanceMeters", 0.0)),
                    duration_seconds=_parse_duration(item.get("duration")),
                    static_duration_seconds=_parse_optional_duration(item.get("staticDuration")),
                    source_status="LIVE",
                    provider_name=self.name,
                )
            )
        return routes

    def distance_matrix(self, waypoints: Sequence[tuple[float, float]]) -> Matrix:
        if len(waypoints) < 2:
            raise ValueError("at least two waypoints are required for a routing matrix")
        body = {
            "origins": [{"waypoint": _waypoint_body(point)} for point in waypoints],
            "destinations": [{"waypoint": _waypoint_body(point)} for point in waypoints],
            "travelMode": "DRIVE",
            "routingPreference": "TRAFFIC_AWARE_OPTIMAL",
        }
        elements = self._post(COMPUTE_ROUTE_MATRIX_URL, body, MATRIX_FIELD_MASK)
        if not isinstance(elements, list):
            raise RoutingProviderError(
                "Google Routes matrix response was not a list of elements", provider_name=self.name
            )

        size = len(waypoints)
        durations: list[list[float | None]] = [[None] * size for _ in range(size)]
        distances: list[list[float | None]] = [[None] * size for _ in range(size)]
        for element in elements:
            origin_index = element.get("originIndex")
            destination_index = element.get("destinationIndex")
            if not isinstance(origin_index, int) or not isinstance(destination_index, int):
                continue
            if not (0 <= origin_index < size and 0 <= destination_index < size):
                continue
            # Elements without ROUTE_EXISTS carry no usable metrics.
            if element.get("condition") not in (None, "ROUTE_EXISTS"):
                continue
            distance = element.get("distanceMeters")
            duration = element.get("duration")
            if isinstance(distance, (int, float)):
                distances[origin_index][destination_index] = float(distance)
            if isinstance(duration, str):
                durations[origin_index][destination_index] = _parse_duration(duration)

        missing = any(
            value is None for matrix in (durations, distances) for row in matrix for value in row
        )
        return Matrix(
            provider_name=self.name,
            duration_seconds=durations,
            distance_meters=distances,
            source_status="PARTIAL" if missing else "LIVE",
        )

    def _post(self, url: str, body: dict[str, Any], field_mask: str) -> Any:
        request = Request(
            url,
            data=json.dumps(body).encode(),
            headers={
                "Content-Type": "application/json",
                "X-Goog-Api-Key": self.api_key,
                "X-Goog-FieldMask": field_mask,
            },
            method="POST",
        )
        try:
            with urlopen(request, timeout=self.timeout_seconds) as response:
                return json.load(response)
        except HTTPError as exc:
            raise RoutingProviderError(
                f"Google Routes request failed with HTTP {exc.code}",
                provider_name=self.name,
                status_code=exc.code,
            ) from exc
        except RoutingProviderError:
            raise
        except Exception as exc:
            raise RoutingProviderError(f"Google Routes request failed: {exc}", provider_name=self.name) from exc


def _waypoint_body(point: tuple[float, float]) -> dict[str, Any]:
    longitude, latitude = point
    return {"location": {"latLng": {"latitude": latitude, "longitude": longitude}}}


def _parse_duration(value: Any) -> float:
    """Parse Google's protobuf-duration strings ("3600s", "61.5s") to seconds."""
    if isinstance(value, str) and value.endswith("s"):
        try:
            return float(value[:-1])
        except ValueError:
            pass
    raise RoutingProviderError(
        f"Google Routes returned an unparsable duration: {value!r}", provider_name="google-routes"
    )


def _parse_optional_duration(value: Any) -> float | None:
    if value is None:
        return None
    return _parse_duration(value)


def decode_polyline(encoded: str) -> list[list[float]]:
    """Decode a Google encoded polyline (precision 1e5) into [[lon, lat], ...]."""
    coordinates: list[list[float]] = []
    index = 0
    latitude = 0
    longitude = 0
    length = len(encoded)
    while index < length:
        latitude, index = _decode_next_value(encoded, index, latitude)
        longitude, index = _decode_next_value(encoded, index, longitude)
        coordinates.append([longitude / 1e5, latitude / 1e5])
    return coordinates


def _decode_next_value(encoded: str, index: int, accumulator: int) -> tuple[int, int]:
    shift = 0
    result = 0
    while True:
        byte = ord(encoded[index]) - 63
        index += 1
        result |= (byte & 0x1F) << shift
        shift += 5
        if byte < 0x20:
            break
    delta = ~(result >> 1) if result & 1 else result >> 1
    return accumulator + delta, index
