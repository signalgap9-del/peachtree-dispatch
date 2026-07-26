"""Network-mocked tests for the VRP route geometry providers."""
from __future__ import annotations

import json

import pytest

from app.vrp.geometry import (
    FallbackRouteGeometryProvider,
    FixtureRouteGeometryProvider,
    OsrmRouteGeometryProvider,
    ResilientRouteGeometryProvider,
)
from app.vrp.models import GeoNode, RouteGeometry


class FakeResponse:
    def __init__(self, payload: bytes):
        self._payload = payload

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return None

    def read(self):
        return self._payload


def _origin() -> GeoNode:
    return GeoNode(node_id="o", label="Atlanta", latitude=33.749, longitude=-84.388)


def _destination() -> GeoNode:
    return GeoNode(node_id="d", label="Savannah", latitude=32.081, longitude=-81.091)


def test_osrm_route_geometry_success(monkeypatch) -> None:
    payload = {
        "routes": [
            {"geometry": {"coordinates": [[-84.0, 33.0], [-81.0, 32.0]]}, "distance": 160934.4, "duration": 3600}
        ]
    }
    monkeypatch.setattr("app.vrp.geometry.urlopen", lambda *a, **k: FakeResponse(json.dumps(payload).encode()))

    leg = OsrmRouteGeometryProvider().route_leg(_origin(), _destination())

    assert leg.source_status == "LIVE"
    assert leg.distance_miles == pytest.approx(100.0)
    assert leg.duration_minutes == pytest.approx(60.0)


def test_osrm_route_geometry_no_routes_raises(monkeypatch) -> None:
    monkeypatch.setattr("app.vrp.geometry.urlopen", lambda *a, **k: FakeResponse(b'{"routes": []}'))

    with pytest.raises(RuntimeError, match="no leg geometry"):
        OsrmRouteGeometryProvider().route_leg(_origin(), _destination())


def test_fallback_route_geometry_is_estimated() -> None:
    leg = FallbackRouteGeometryProvider().route_leg(_origin(), _destination())
    assert leg.source_status == "ESTIMATED"
    assert leg.distance_miles > 0
    assert len(leg.coordinates) == 2


def test_resilient_provider_uses_primary() -> None:
    class Boom:
        def route_leg(self, origin, destination):
            raise AssertionError("fallback should not be used")

    provider = ResilientRouteGeometryProvider(FallbackRouteGeometryProvider(), Boom())
    assert provider.route_leg(_origin(), _destination()).source_status == "ESTIMATED"


def test_resilient_provider_recovers_from_primary_failure() -> None:
    class Boom:
        def route_leg(self, origin, destination):
            raise RuntimeError("primary down")

    provider = ResilientRouteGeometryProvider(Boom(), FallbackRouteGeometryProvider())
    assert provider.route_leg(_origin(), _destination()).source_status == "ESTIMATED"


def test_fixture_provider_returns_configured_leg() -> None:
    leg = RouteGeometry(
        coordinates=[[-84.0, 33.0], [-81.0, 32.0]],
        distance_miles=100.0,
        duration_minutes=60.0,
        source_status="LIVE",
    )
    provider = FixtureRouteGeometryProvider({("o", "d"): leg})
    assert provider.route_leg(_origin(), _destination()) is leg


def test_fixture_provider_missing_leg_raises() -> None:
    provider = FixtureRouteGeometryProvider({})
    with pytest.raises(ValueError, match="missing fixture leg"):
        provider.route_leg(_origin(), _destination())
