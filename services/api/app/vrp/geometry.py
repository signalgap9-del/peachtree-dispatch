from __future__ import annotations

import json
from typing import Protocol
from urllib.parse import urlencode
from urllib.request import Request

from .matrix import USER_AGENT, _haversine_meters
from .models import GeoNode, RouteGeometry
from ..outbound_http import safe_urlopen as urlopen


class RouteGeometryProvider(Protocol):
    def route_leg(self, origin: GeoNode, destination: GeoNode) -> RouteGeometry:
        ...


class OsrmRouteGeometryProvider:
    def __init__(self, base_url: str = "https://router.project-osrm.org", timeout_seconds: float = 12.0):
        self.base_url = base_url.rstrip("/")
        self.timeout_seconds = timeout_seconds

    def route_leg(self, origin: GeoNode, destination: GeoNode) -> RouteGeometry:
        coordinates = f"{origin.longitude},{origin.latitude};{destination.longitude},{destination.latitude}"
        params = urlencode({"overview": "full", "geometries": "geojson", "steps": "true", "alternatives": "false"})
        request = Request(
            f"{self.base_url}/route/v1/driving/{coordinates}?{params}",
            headers={"User-Agent": USER_AGENT},
        )
        with urlopen(request, timeout=self.timeout_seconds) as response:
            routes = json.load(response).get("routes", [])
        if not routes:
            raise RuntimeError("routing provider returned no leg geometry")
        route = routes[0]
        return RouteGeometry(
            coordinates=route["geometry"]["coordinates"],
            distance_miles=round(route["distance"] / 1609.344, 1),
            duration_minutes=round(route["duration"] / 60, 1),
            source_status="LIVE",
        )


class FallbackRouteGeometryProvider:
    def route_leg(self, origin: GeoNode, destination: GeoNode) -> RouteGeometry:
        meters = _haversine_meters(origin, destination)
        duration_seconds = meters / (48 * 1609.344 / 3600)
        return RouteGeometry(
            coordinates=[[origin.longitude, origin.latitude], [destination.longitude, destination.latitude]],
            distance_miles=round(meters / 1609.344, 1),
            duration_minutes=round(duration_seconds / 60, 1),
            source_status="ESTIMATED",
        )


class ResilientRouteGeometryProvider:
    def __init__(self, primary: RouteGeometryProvider, fallback: RouteGeometryProvider):
        self.primary = primary
        self.fallback = fallback

    def route_leg(self, origin: GeoNode, destination: GeoNode) -> RouteGeometry:
        try:
            return self.primary.route_leg(origin, destination)
        except Exception:
            return self.fallback.route_leg(origin, destination)


class FixtureRouteGeometryProvider:
    def __init__(self, legs: dict[tuple[str, str], RouteGeometry]):
        self.legs = legs

    def route_leg(self, origin: GeoNode, destination: GeoNode) -> RouteGeometry:
        key = (origin.node_id, destination.node_id)
        if key not in self.legs:
            raise ValueError(f"missing fixture leg {key}")
        return self.legs[key]
