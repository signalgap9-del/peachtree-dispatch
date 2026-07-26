"""Network-mocked tests for the WZDx/511 road event risk provider."""
from __future__ import annotations

import json

import pytest

from app.outbound_http import OutboundRequestError
from app.vrp.edge_risk import ConstantEdgeRiskProvider
from app.vrp.models import GeoNode
from app.vrp.road_event_risk import (
    RoadEvent,
    RoadEventEdgeRiskProvider,
    StaticRoadEventProvider,
    WzdxGeoJsonRoadEventProvider,
    _coordinate_pair,
    _humanize,
    _representative_coordinate,
    _risk_score,
    _safe_source,
    build_default_road_event_edge_risk_provider,
    road_events_from_geojson,
)


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
    return GeoNode(node_id="o", label="O", latitude=33.0, longitude=-84.0)


def _destination() -> GeoNode:
    return GeoNode(node_id="d", label="D", latitude=33.1, longitude=-84.1)


# ---------------------------------------------------------------------------
# from_env
# ---------------------------------------------------------------------------

def test_from_env_returns_none_without_urls(monkeypatch) -> None:
    monkeypatch.delenv("VRP_ROAD_EVENT_FEED_URLS", raising=False)
    assert WzdxGeoJsonRoadEventProvider.from_env() is None


def test_from_env_parses_urls_and_settings(monkeypatch) -> None:
    monkeypatch.setenv("VRP_ROAD_EVENT_FEED_URLS", "https://a.test/feed, https://b.test/feed")
    monkeypatch.setenv("VRP_ROAD_EVENT_CORRIDOR_RADIUS_MILES", "25")

    provider = WzdxGeoJsonRoadEventProvider.from_env()

    assert provider is not None
    assert provider.feed_urls == ["https://a.test/feed", "https://b.test/feed"]
    assert provider.corridor_radius_miles == 25


# ---------------------------------------------------------------------------
# _fetch_feed
# ---------------------------------------------------------------------------

def test_fetch_feed_parses_geojson(monkeypatch) -> None:
    payload = {
        "features": [
            {
                "geometry": {"type": "Point", "coordinates": [-83.6, 32.8]},
                "properties": {"event_type": "lane_closure", "vehicle_impact": "all_lanes_closed"},
            }
        ]
    }
    monkeypatch.setattr("app.vrp.road_event_risk.safe_urlopen", lambda *a, **k: FakeResponse(json.dumps(payload).encode()))

    provider = WzdxGeoJsonRoadEventProvider(feed_urls=["https://x.test/feed"])
    events = provider._fetch_feed("https://x.test/feed")

    assert len(events) == 1
    assert events[0].risk_score == 85


def test_fetch_feed_returns_empty_on_network_error(monkeypatch) -> None:
    def blocked(*args, **kwargs):
        raise OutboundRequestError("blocked")

    monkeypatch.setattr("app.vrp.road_event_risk.safe_urlopen", blocked)

    provider = WzdxGeoJsonRoadEventProvider(feed_urls=["https://x.test/feed"])
    assert provider._fetch_feed("https://x.test/feed") == []


def test_fetch_feed_returns_empty_on_bad_json(monkeypatch) -> None:
    monkeypatch.setattr("app.vrp.road_event_risk.safe_urlopen", lambda *a, **k: FakeResponse(b"not json"))

    provider = WzdxGeoJsonRoadEventProvider(feed_urls=["https://x.test/feed"])
    assert provider._fetch_feed("https://x.test/feed") == []


# ---------------------------------------------------------------------------
# caching + corridor filtering
# ---------------------------------------------------------------------------

def test_load_events_caches_within_ttl(monkeypatch) -> None:
    calls = 0

    def fake_fetch(url):
        nonlocal calls
        calls += 1
        return [
            RoadEvent(
                event_id="e",
                event_type="Road Closure",
                severity="Full",
                latitude=33.05,
                longitude=-84.05,
                source="s",
                description="d",
                risk_score=85,
            )
        ]

    provider = WzdxGeoJsonRoadEventProvider(feed_urls=["https://x.test/feed"])
    monkeypatch.setattr(provider, "_fetch_feed", fake_fetch)

    assert len(provider.events_near_edge(_origin(), _destination())) == 1
    provider.events_near_edge(_origin(), _destination())

    assert calls == 1


# ---------------------------------------------------------------------------
# build_default_road_event_edge_risk_provider
# ---------------------------------------------------------------------------

def test_build_default_returns_base_without_env(monkeypatch) -> None:
    monkeypatch.delenv("VRP_ROAD_EVENT_FEED_URLS", raising=False)
    base = ConstantEdgeRiskProvider(5)
    assert build_default_road_event_edge_risk_provider(base) is base


def test_build_default_wraps_base_with_env(monkeypatch) -> None:
    monkeypatch.setenv("VRP_ROAD_EVENT_FEED_URLS", "https://x.test/feed")
    base = ConstantEdgeRiskProvider(5)
    provider = build_default_road_event_edge_risk_provider(base)
    assert isinstance(provider, RoadEventEdgeRiskProvider)


def test_edge_risk_returns_base_when_no_events() -> None:
    base = ConstantEdgeRiskProvider(7)
    provider = RoadEventEdgeRiskProvider(base, StaticRoadEventProvider([]))

    risk = provider.score_edge(_origin(), _destination())

    assert risk.weather_risk_score == 7
    assert risk.traffic_risk_score == 0


# ---------------------------------------------------------------------------
# road_events_from_geojson edge cases
# ---------------------------------------------------------------------------

def test_geojson_non_dict_returns_empty() -> None:
    assert road_events_from_geojson([], "s") == []


def test_geojson_without_features_returns_empty() -> None:
    assert road_events_from_geojson({"type": "FeatureCollection"}, "s") == []


def test_geojson_skips_unusable_features() -> None:
    decoded = {
        "features": [
            "not-a-dict",
            {"geometry": None, "properties": {}},
            {"geometry": {"type": "Point", "coordinates": [-400, 0]}, "properties": {}},
        ]
    }
    assert road_events_from_geojson(decoded, "s") == []


def test_geojson_respects_limit() -> None:
    features = [
        {"geometry": {"type": "Point", "coordinates": [-84 + i * 0.01, 33]}, "properties": {}}
        for i in range(10)
    ]
    assert len(road_events_from_geojson({"features": features}, "s", limit=3)) == 3


# ---------------------------------------------------------------------------
# coordinate / scoring helpers
# ---------------------------------------------------------------------------

def test_representative_coordinate_handles_linestring() -> None:
    geometry = {"type": "LineString", "coordinates": [[-84, 33], [-82, 35]]}
    lon, lat = _representative_coordinate(geometry)
    assert lon == pytest.approx(-83)
    assert lat == pytest.approx(34)


def test_representative_coordinate_handles_multilinestring() -> None:
    geometry = {"type": "MultiLineString", "coordinates": [[[-84, 33], [-82, 35]]]}
    assert _representative_coordinate(geometry) is not None


def test_representative_coordinate_rejects_non_dict() -> None:
    assert _representative_coordinate("nope") is None


def test_coordinate_pair_validates_range() -> None:
    assert _coordinate_pair([-400, 0]) is None
    assert _coordinate_pair([0, 0]) == (0.0, 0.0)


def test_risk_score_keyword_branches() -> None:
    assert _risk_score("Road Closure", "full", {}) == 85
    assert _risk_score("Crash", "minor", {}) == 75
    assert _risk_score("Lane restriction", "minor", {}) == 60
    assert _risk_score("Work zone", "minor", {}) == 55
    assert _risk_score("Other", "unknown", {}) == 35


def test_humanize_formats_and_defaults() -> None:
    assert _humanize("lane_closure") == "Lane Closure"
    assert _humanize("") == "Road Event"
    assert _humanize(None) == "Road Event"


def test_safe_source_strips_query_string() -> None:
    assert _safe_source("https://x.test/feed?key=secret") == "https://x.test/feed"
