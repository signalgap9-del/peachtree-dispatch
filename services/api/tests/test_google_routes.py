"""Network-mocked tests for the Google Routes provider and provider factory.

The outbound HTTP layer is replaced with fixture payloads shaped like real
 Google Routes API v2 responses so request construction, response mapping,
and error handling can be exercised deterministically.
"""
from __future__ import annotations

import json
import logging
from urllib.error import HTTPError

import pytest

from app.routing.factory import get_routing_provider
from app.routing.google_routes import COMPUTE_ROUTES_URL, GoogleRoutesProvider, decode_polyline
from app.routing.osrm import OsrmProvider
from app.routing.provider import RoutingProviderError


class FakeResponse:
    """Minimal context-manager response whose body is read by json.load."""

    def __init__(self, payload: bytes):
        self._payload = payload

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return None

    def read(self):
        return self._payload


def _json_response(obj) -> FakeResponse:
    return FakeResponse(json.dumps(obj).encode())


# Google's canonical encoded-polyline documentation example (precision 1e5).
ENCODED_POLYLINE = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
DECODED_COORDINATES = [[-120.2, 38.5], [-120.95, 40.7], [-126.453, 43.252]]


def _route_payload(
    distance_meters: int = 160934,
    duration: str = "3600s",
    static_duration: str | None = "3300s",
) -> dict:
    route = {
        "distanceMeters": distance_meters,
        "duration": duration,
        "polyline": {"encodedPolyline": ENCODED_POLYLINE},
        "routeLabels": ["route-1"],
        "routeToken": "token-abc",
    }
    if static_duration is not None:
        route["staticDuration"] = static_duration
    return route


def _capture(monkeypatch, payload):
    """Patch the Google provider's urlopen and capture the outgoing request."""
    captured: dict = {}

    def fake_urlopen(request, timeout=None):
        captured["request"] = request
        captured["timeout"] = timeout
        return _json_response(payload)

    monkeypatch.setattr("app.routing.google_routes.urlopen", fake_urlopen)
    return captured


# ---------------------------------------------------------------------------
# decode_polyline
# ---------------------------------------------------------------------------

def test_decode_polyline_matches_documentation_example() -> None:
    coordinates = decode_polyline(ENCODED_POLYLINE)

    assert len(coordinates) == len(DECODED_COORDINATES)
    for actual, expected in zip(coordinates, DECODED_COORDINATES, strict=True):
        assert actual[0] == pytest.approx(expected[0])
        assert actual[1] == pytest.approx(expected[1])


def test_decode_polyline_empty_string() -> None:
    assert decode_polyline("") == []


# ---------------------------------------------------------------------------
# GoogleRoutesProvider.route
# ---------------------------------------------------------------------------

def test_route_maps_compute_routes_response(monkeypatch) -> None:
    captured = _capture(monkeypatch, {"routes": [_route_payload()]})
    provider = GoogleRoutesProvider(api_key="test-key")

    routes = provider.route([(-120.2, 38.5), (-126.453, 43.252)])

    assert len(routes) == 1
    route = routes[0]
    assert route.provider_name == "google-routes"
    assert route.source_status == "LIVE"
    assert route.distance_meters == 160934
    assert route.duration_seconds == 3600
    assert route.static_duration_seconds == 3300
    assert route.coordinates[0][0] == pytest.approx(-120.2)
    assert route.coordinates[-1][1] == pytest.approx(43.252)


def test_route_sends_auth_field_mask_and_traffic_preference(monkeypatch) -> None:
    captured = _capture(monkeypatch, {"routes": [_route_payload()]})
    provider = GoogleRoutesProvider(api_key="test-key")

    provider.route([(-120.2, 38.5), (-126.453, 43.252)])

    request = captured["request"]
    assert request.full_url == COMPUTE_ROUTES_URL
    # urllib capitalizes header names; lookups are case-insensitive on the wire.
    assert request.get_header("X-goog-api-key") == "test-key"
    field_mask = request.get_header("X-goog-fieldmask")
    assert "routes.duration" in field_mask
    assert "routes.distanceMeters" in field_mask
    assert "routes.polyline.encodedPolyline" in field_mask
    body = json.loads(request.data.decode())
    assert body["travelMode"] == "DRIVE"
    assert body["routingPreference"] == "TRAFFIC_AWARE_OPTIMAL"
    assert body["origin"]["location"]["latLng"] == {"latitude": 38.5, "longitude": -120.2}
    assert body["destination"]["location"]["latLng"] == {"latitude": 43.252, "longitude": -126.453}
    assert "intermediates" not in body


def test_route_includes_intermediates_and_alternatives_flag(monkeypatch) -> None:
    captured = _capture(monkeypatch, {"routes": [_route_payload()]})
    provider = GoogleRoutesProvider(api_key="test-key")

    provider.route([(-84.0, 33.0), (-83.0, 32.5), (-81.0, 32.0)], alternatives=True)

    body = json.loads(captured["request"].data.decode())
    assert body["computeAlternativeRoutes"] is True
    assert body["intermediates"] == [{"location": {"latLng": {"latitude": 32.5, "longitude": -83.0}}}]


def test_route_maps_multiple_alternatives(monkeypatch) -> None:
    payload = {"routes": [_route_payload(duration="3600s"), _route_payload(distance_meters=170000, duration="3900s")]}
    _capture(monkeypatch, payload)
    provider = GoogleRoutesProvider(api_key="test-key")

    routes = provider.route([(-84.0, 33.0), (-81.0, 32.0)], alternatives=True)

    assert [route.duration_seconds for route in routes] == [3600, 3900]
    assert routes[1].distance_meters == 170000


def test_route_static_duration_is_optional(monkeypatch) -> None:
    _capture(monkeypatch, {"routes": [_route_payload(static_duration=None)]})
    provider = GoogleRoutesProvider(api_key="test-key")

    routes = provider.route([(-84.0, 33.0), (-81.0, 32.0)])

    assert routes[0].static_duration_seconds is None


def test_route_requires_two_waypoints() -> None:
    provider = GoogleRoutesProvider(api_key="test-key")
    with pytest.raises(ValueError, match="at least two waypoints"):
        provider.route([(-84.0, 33.0)])


def test_route_missing_routes_key_raises(monkeypatch) -> None:
    _capture(monkeypatch, {"unexpected": True})
    provider = GoogleRoutesProvider(api_key="test-key")

    with pytest.raises(RoutingProviderError, match="did not contain routes"):
        provider.route([(-84.0, 33.0), (-81.0, 32.0)])


def test_route_missing_polyline_raises(monkeypatch) -> None:
    route = _route_payload()
    del route["polyline"]
    _capture(monkeypatch, {"routes": [route]})
    provider = GoogleRoutesProvider(api_key="test-key")

    with pytest.raises(RoutingProviderError, match="polyline"):
        provider.route([(-84.0, 33.0), (-81.0, 32.0)])


@pytest.mark.parametrize("status", [403, 429, 500])
def test_route_http_errors_surface_status_code(monkeypatch, status: int) -> None:
    def fake_urlopen(request, timeout=None):
        raise HTTPError(COMPUTE_ROUTES_URL, status, "error", None, None)

    monkeypatch.setattr("app.routing.google_routes.urlopen", fake_urlopen)
    provider = GoogleRoutesProvider(api_key="test-key")

    with pytest.raises(RoutingProviderError) as excinfo:
        provider.route([(-84.0, 33.0), (-81.0, 32.0)])

    assert excinfo.value.status_code == status
    assert excinfo.value.provider_name == "google-routes"
    assert f"HTTP {status}" in str(excinfo.value)


def test_constructor_requires_api_key(monkeypatch) -> None:
    monkeypatch.delenv("GOOGLE_ROUTES_API_KEY", raising=False)
    with pytest.raises(ValueError, match="API key"):
        GoogleRoutesProvider()


def test_constructor_reads_key_from_env(monkeypatch) -> None:
    monkeypatch.setenv("GOOGLE_ROUTES_API_KEY", "env-key")
    assert GoogleRoutesProvider().api_key == "env-key"


# ---------------------------------------------------------------------------
# GoogleRoutesProvider.distance_matrix
# ---------------------------------------------------------------------------

def _matrix_elements() -> list[dict]:
    return [
        {"originIndex": 0, "destinationIndex": 0, "condition": "ROUTE_EXISTS", "distanceMeters": 0, "duration": "0s"},
        {"originIndex": 0, "destinationIndex": 1, "condition": "ROUTE_EXISTS", "distanceMeters": 12000, "duration": "600s"},
        {"originIndex": 1, "destinationIndex": 0, "condition": "ROUTE_EXISTS", "distanceMeters": 12100, "duration": "610s"},
        {"originIndex": 1, "destinationIndex": 1, "condition": "ROUTE_EXISTS", "distanceMeters": 0, "duration": "0s"},
    ]


def test_distance_matrix_maps_route_matrix_response(monkeypatch) -> None:
    captured = _capture(monkeypatch, _matrix_elements())
    provider = GoogleRoutesProvider(api_key="test-key")

    matrix = provider.distance_matrix([(-84.0, 33.0), (-81.0, 32.0)])

    assert matrix.provider_name == "google-routes"
    assert matrix.source_status == "LIVE"
    assert matrix.duration_seconds == [[0.0, 600.0], [610.0, 0.0]]
    assert matrix.distance_meters == [[0.0, 12000.0], [12100.0, 0.0]]
    body = json.loads(captured["request"].data.decode())
    assert len(body["origins"]) == 2
    assert len(body["destinations"]) == 2
    assert "originIndex" in captured["request"].get_header("X-goog-fieldmask")


def test_distance_matrix_is_partial_when_a_pair_has_no_route(monkeypatch) -> None:
    elements = _matrix_elements()
    elements[1] = {"originIndex": 0, "destinationIndex": 1, "condition": "ROUTE_NOT_FOUND", "status": {}}
    _capture(monkeypatch, elements)
    provider = GoogleRoutesProvider(api_key="test-key")

    matrix = provider.distance_matrix([(-84.0, 33.0), (-81.0, 32.0)])

    assert matrix.source_status == "PARTIAL"
    assert matrix.duration_seconds[0][1] is None
    assert matrix.distance_meters[0][1] is None
    assert matrix.duration_seconds[1][0] == 610.0


def test_distance_matrix_requires_two_waypoints() -> None:
    provider = GoogleRoutesProvider(api_key="test-key")
    with pytest.raises(ValueError, match="at least two waypoints"):
        provider.distance_matrix([(-84.0, 33.0)])


def test_distance_matrix_http_error_surfaces_status_code(monkeypatch) -> None:
    def fake_urlopen(request, timeout=None):
        raise HTTPError("https://routes.googleapis.com/distanceMatrix/v2:computeRouteMatrix", 429, "rate limited", None, None)

    monkeypatch.setattr("app.routing.google_routes.urlopen", fake_urlopen)
    provider = GoogleRoutesProvider(api_key="test-key")

    with pytest.raises(RoutingProviderError) as excinfo:
        provider.distance_matrix([(-84.0, 33.0), (-81.0, 32.0)])

    assert excinfo.value.status_code == 429


# ---------------------------------------------------------------------------
# Provider factory
# ---------------------------------------------------------------------------

def test_factory_defaults_to_osrm_without_key() -> None:
    assert isinstance(get_routing_provider(), OsrmProvider)


def test_factory_uses_google_when_key_present(monkeypatch) -> None:
    monkeypatch.setenv("GOOGLE_ROUTES_API_KEY", "test-key")
    provider = get_routing_provider()
    assert isinstance(provider, GoogleRoutesProvider)
    assert provider.api_key == "test-key"


def test_factory_uses_google_when_explicitly_requested(monkeypatch) -> None:
    monkeypatch.setenv("ROUTING_PROVIDER", "google")
    monkeypatch.setenv("GOOGLE_ROUTES_API_KEY", "test-key")
    assert isinstance(get_routing_provider(), GoogleRoutesProvider)


def test_factory_falls_back_to_osrm_when_google_requested_without_key(monkeypatch, caplog) -> None:
    monkeypatch.setenv("ROUTING_PROVIDER", "google")
    monkeypatch.delenv("GOOGLE_ROUTES_API_KEY", raising=False)

    with caplog.at_level(logging.WARNING, logger="app.routing.factory"):
        provider = get_routing_provider()

    assert isinstance(provider, OsrmProvider)
    assert any("falling back to OSRM" in record.message for record in caplog.records)


def test_factory_explicit_osrm_wins_even_with_key(monkeypatch) -> None:
    monkeypatch.setenv("ROUTING_PROVIDER", "osrm")
    monkeypatch.setenv("GOOGLE_ROUTES_API_KEY", "test-key")
    assert isinstance(get_routing_provider(), OsrmProvider)


def test_factory_unknown_value_falls_back_to_osrm(monkeypatch, caplog) -> None:
    monkeypatch.setenv("ROUTING_PROVIDER", "mapbox")

    with caplog.at_level(logging.WARNING, logger="app.routing.factory"):
        provider = get_routing_provider()

    assert isinstance(provider, OsrmProvider)
    assert any("Unknown ROUTING_PROVIDER" in record.message for record in caplog.records)


# ---------------------------------------------------------------------------
# VRP wiring: the matrix adapter exposes the selected provider to the solver
# ---------------------------------------------------------------------------

def test_build_default_matrix_provider_uses_google_adapter_when_key_present(monkeypatch) -> None:
    from app.vrp.matrix import FallbackMatrixProvider, RoutingProviderMatrixAdapter, build_default_matrix_provider

    monkeypatch.setenv("GOOGLE_ROUTES_API_KEY", "test-key")

    provider = build_default_matrix_provider()

    assert isinstance(provider, FallbackMatrixProvider)
    assert isinstance(provider.primary, RoutingProviderMatrixAdapter)
    assert isinstance(provider.primary.provider, GoogleRoutesProvider)


def test_matrix_adapter_builds_routing_matrix_from_google_response(monkeypatch) -> None:
    from app.vrp.matrix import RoutingProviderMatrixAdapter
    from app.vrp.models import GeoNode

    _capture(monkeypatch, _matrix_elements())
    adapter = RoutingProviderMatrixAdapter(GoogleRoutesProvider(api_key="test-key"))
    nodes = [
        GeoNode(node_id="n0", label="Atlanta", latitude=33.0, longitude=-84.0),
        GeoNode(node_id="n1", label="Savannah", latitude=32.0, longitude=-81.0),
    ]

    matrix = adapter.build_matrix(nodes)

    assert matrix.provider == "google-routes"
    assert matrix.node_ids == ["n0", "n1"]
    assert matrix.source_status == "LIVE"
    assert matrix.duration_seconds[0][1] == 600.0
    assert matrix.provider_request_id is not None
    assert matrix.provider_request_id.startswith("google-routes_")
