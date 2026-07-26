"""Network-mocked tests for the OSRM table matrix provider and fallbacks."""
from __future__ import annotations

import json

import pytest

from app.vrp.matrix import (
    FallbackMatrixProvider,
    FixtureMatrixProvider,
    HaversineMatrixProvider,
    MatrixProviderError,
    OsrmTableMatrixProvider,
    _has_missing,
    _haversine_meters,
    build_default_matrix_provider,
)
from app.vrp.models import GeoNode, RoutingMatrix


class FakeResponse:
    def __init__(self, payload: bytes):
        self._payload = payload

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return None

    def read(self):
        return self._payload


def _nodes() -> list[GeoNode]:
    return [
        GeoNode(node_id="n0", label="Atlanta", latitude=33.749, longitude=-84.388),
        GeoNode(node_id="n1", label="Savannah", latitude=32.081, longitude=-81.091),
    ]


def test_osrm_table_success_is_live(monkeypatch) -> None:
    payload = {
        "durations": [[0.0, 600.0], [600.0, 0.0]],
        "distances": [[0.0, 12000.0], [12000.0, 0.0]],
    }
    monkeypatch.setattr("app.vrp.matrix.urlopen", lambda *a, **k: FakeResponse(json.dumps(payload).encode()))

    matrix = OsrmTableMatrixProvider().build_matrix(_nodes())

    assert matrix.provider == "osrm-table"
    assert matrix.source_status == "LIVE"
    assert matrix.node_ids == ["n0", "n1"]
    assert matrix.duration_seconds == payload["durations"]
    assert matrix.provider_request_id.startswith("osrm_")


def test_osrm_table_with_gaps_is_partial(monkeypatch) -> None:
    payload = {
        "durations": [[0.0, None], [600.0, 0.0]],
        "distances": [[0.0, 12000.0], [12000.0, 0.0]],
    }
    monkeypatch.setattr("app.vrp.matrix.urlopen", lambda *a, **k: FakeResponse(json.dumps(payload).encode()))

    matrix = OsrmTableMatrixProvider().build_matrix(_nodes())

    assert matrix.source_status == "PARTIAL"


def test_osrm_table_malformed_response_raises(monkeypatch) -> None:
    monkeypatch.setattr("app.vrp.matrix.urlopen", lambda *a, **k: FakeResponse(b'{"unexpected": true}'))

    with pytest.raises(MatrixProviderError, match="duration and distance"):
        OsrmTableMatrixProvider().build_matrix(_nodes())


def test_osrm_table_network_error_raises(monkeypatch) -> None:
    def boom(*args, **kwargs):
        raise RuntimeError("connection refused")

    monkeypatch.setattr("app.vrp.matrix.urlopen", boom)

    with pytest.raises(MatrixProviderError, match="request failed"):
        OsrmTableMatrixProvider().build_matrix(_nodes())


def test_osrm_table_requires_two_nodes() -> None:
    with pytest.raises(ValueError, match="at least two nodes"):
        OsrmTableMatrixProvider().build_matrix([_nodes()[0]])


def test_haversine_matrix_is_estimated_and_zero_diagonal() -> None:
    matrix = HaversineMatrixProvider().build_matrix(_nodes())

    assert matrix.source_status == "ESTIMATED"
    assert matrix.provider == "haversine-estimate"
    assert matrix.duration_seconds[0][0] == 0.0
    assert matrix.distance_meters[0][0] == 0.0
    assert matrix.distance_meters[0][1] > 0
    assert matrix.duration_seconds[0][1] > 0


def test_haversine_requires_two_nodes() -> None:
    with pytest.raises(ValueError, match="at least two nodes"):
        HaversineMatrixProvider().build_matrix([_nodes()[0]])


def test_fallback_provider_uses_primary_when_healthy() -> None:
    class Boom:
        def build_matrix(self, nodes):
            raise AssertionError("fallback should not be used")

    provider = FallbackMatrixProvider(HaversineMatrixProvider(), Boom())
    assert provider.build_matrix(_nodes()).source_status == "ESTIMATED"


def test_fallback_provider_recovers_from_primary_failure() -> None:
    class Boom:
        def build_matrix(self, nodes):
            raise RuntimeError("primary down")

    provider = FallbackMatrixProvider(Boom(), HaversineMatrixProvider())
    assert provider.build_matrix(_nodes()).source_status == "ESTIMATED"


def test_fixture_provider_rejects_node_order_mismatch() -> None:
    matrix = RoutingMatrix(
        provider="fixture",
        node_ids=["x0", "x1"],
        duration_seconds=[[0, 1], [1, 0]],
        distance_meters=[[0, 1], [1, 0]],
        source_status="ESTIMATED",
    )
    provider = FixtureMatrixProvider(matrix)

    with pytest.raises(ValueError, match="mismatch"):
        provider.build_matrix(_nodes())


def test_build_default_matrix_provider_wraps_osrm_with_fallback(monkeypatch) -> None:
    monkeypatch.delenv("OSRM_BASE_URL", raising=False)
    provider = build_default_matrix_provider()
    assert isinstance(provider, FallbackMatrixProvider)
    assert isinstance(provider.primary, OsrmTableMatrixProvider)
    assert isinstance(provider.fallback, HaversineMatrixProvider)


def test_has_missing_detects_none() -> None:
    assert _has_missing([[0, None], [1, 2]]) is True
    assert _has_missing([[0, 1], [1, 0]]) is False


def test_haversine_meters_zero_for_same_point() -> None:
    node = GeoNode(node_id="a", label="A", latitude=33.7, longitude=-84.3)
    assert _haversine_meters(node, node) == pytest.approx(0.0)


def test_haversine_meters_positive_for_distance() -> None:
    a = GeoNode(node_id="a", label="A", latitude=33.749, longitude=-84.388)
    b = GeoNode(node_id="b", label="B", latitude=32.081, longitude=-81.091)
    # Atlanta -> Savannah is roughly 358km by great-circle distance.
    assert 300_000 < _haversine_meters(a, b) < 400_000
