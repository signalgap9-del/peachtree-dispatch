from __future__ import annotations

from typing import Protocol

from ..routing.factory import get_routing_provider
from ..routing.osrm import DEFAULT_OSRM_BASE_URL, OsrmProvider
from ..routing.provider import METERS_PER_MILE, Route, RoutingProvider
from .matrix import _haversine_meters
from .models import GeoNode, RouteGeometry


class RouteGeometryProvider(Protocol):
    def route_leg(self, origin: GeoNode, destination: GeoNode) -> RouteGeometry:
        ...


def _leg_waypoints(origin: GeoNode, destination: GeoNode) -> list[tuple[float, float]]:
    return [(origin.longitude, origin.latitude), (destination.longitude, destination.latitude)]


def _geometry_from_route(route: Route) -> RouteGeometry:
    return RouteGeometry(
        coordinates=route.coordinates,
        distance_miles=round(route.distance_meters / METERS_PER_MILE, 1),
        duration_minutes=round(route.duration_seconds / 60, 1),
        source_status="LIVE",
    )


class OsrmRouteGeometryProvider:
    """OSRM single-leg geometry, via the shared OSRM routing provider."""

    def __init__(self, base_url: str = DEFAULT_OSRM_BASE_URL, timeout_seconds: float = 12.0):
        self._osrm = OsrmProvider(base_url=base_url, timeout_seconds=timeout_seconds)

    def route_leg(self, origin: GeoNode, destination: GeoNode) -> RouteGeometry:
        routes = self._osrm.route(_leg_waypoints(origin, destination), steps=True)
        if not routes:
            raise RuntimeError("routing provider returned no leg geometry")
        return _geometry_from_route(routes[0])


class RoutingProviderGeometryAdapter:
    """Adapts any ``routing.RoutingProvider`` to single-leg VRP geometry."""

    def __init__(self, provider: RoutingProvider):
        self.provider = provider

    def route_leg(self, origin: GeoNode, destination: GeoNode) -> RouteGeometry:
        routes = self.provider.route(_leg_waypoints(origin, destination))
        if not routes:
            raise RuntimeError("routing provider returned no leg geometry")
        return _geometry_from_route(routes[0])


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


def build_default_route_geometry_provider() -> RouteGeometryProvider:
    """Leg geometry wired through the routing provider factory.

    OSRM keeps its dedicated adapter (identical wire request as before); any
    other backend flows through the generic adapter. The haversine fallback
    keeps multi-stop plans working when the live provider is down.
    """
    provider = get_routing_provider()
    primary: RouteGeometryProvider
    if isinstance(provider, OsrmProvider):
        primary = OsrmRouteGeometryProvider(
            base_url=provider.base_url, timeout_seconds=provider.timeout_seconds
        )
    else:
        primary = RoutingProviderGeometryAdapter(provider)
    return ResilientRouteGeometryProvider(primary=primary, fallback=FallbackRouteGeometryProvider())
