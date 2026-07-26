"""Network-mocked tests for NWS alert parsing and scoring in app.hazards."""
from __future__ import annotations

import json

import pytest

from app.hazards import (
    _alert,
    _centroid,
    _geometry_contains,
    _nws_alerts_result,
    _ring_contains,
    alerts_for_point,
    alerts_for_point_result,
    classify_event,
    national_alerts,
    national_alerts_result,
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


def _feature(event: str = "Flood Warning", severity: str = "Severe", geometry=None) -> dict:
    return {
        "properties": {
            "id": "alert-1",
            "event": event,
            "severity": severity,
            "urgency": "Immediate",
            "certainty": "Observed",
            "headline": f"{event} headline",
            "areaDesc": "South Georgia",
            "instruction": "Seek higher ground",
        },
        "geometry": geometry,
    }


# ---------------------------------------------------------------------------
# _nws_alerts_result
# ---------------------------------------------------------------------------

def test_nws_alerts_result_success_is_live(monkeypatch) -> None:
    payload = {"features": [_feature()]}
    monkeypatch.setattr("app.hazards.urlopen", lambda *a, **k: FakeResponse(json.dumps(payload).encode()))

    features, status = _nws_alerts_result("status=actual")

    assert status == "LIVE"
    assert len(features) == 1


def test_nws_alerts_result_failure_is_unavailable(monkeypatch) -> None:
    def boom(*args, **kwargs):
        raise RuntimeError("NWS down")

    monkeypatch.setattr("app.hazards.urlopen", boom)

    features, status = _nws_alerts_result("status=actual")

    assert features == []
    assert status == "UNAVAILABLE"


# ---------------------------------------------------------------------------
# point / national alert wrappers
# ---------------------------------------------------------------------------

def test_alerts_for_point_result_parses_and_scores(monkeypatch) -> None:
    monkeypatch.setattr(
        "app.hazards._nws_alerts_result",
        lambda query: ([_feature(event="Tornado Warning", severity="Extreme")], "LIVE"),
    )

    alerts, status = alerts_for_point_result(33.0, -84.0)

    assert status == "LIVE"
    assert alerts[0].score == 100  # Extreme(100) + Tornado bonus(15) capped at 100
    assert alerts[0].category == "TORNADO"
    assert alerts[0].instruction == "Seek higher ground"


def test_alerts_for_point_returns_only_alerts(monkeypatch) -> None:
    monkeypatch.setattr("app.hazards._nws_alerts_result", lambda query: ([_feature()], "LIVE"))
    assert len(alerts_for_point(33.0, -84.0)) == 1


def test_national_alerts_result_and_wrapper(monkeypatch) -> None:
    monkeypatch.setattr(
        "app.hazards._nws_alerts_result",
        lambda query: ([_feature(), _feature(event="High Wind Warning")], "LIVE"),
    )
    alerts, status = national_alerts_result()
    assert status == "LIVE"
    assert len(alerts) == 2
    assert len(national_alerts()) == 2


# ---------------------------------------------------------------------------
# classify_event
# ---------------------------------------------------------------------------

@pytest.mark.parametrize(
    "event,expected",
    [
        ("Tornado Warning", "TORNADO"),
        ("Hurricane Warning", "TROPICAL_CYCLONE"),
        ("Tropical Storm Watch", "TROPICAL_CYCLONE"),
        ("Flash Flood Warning", "FLOOD"),
        ("Severe Thunderstorm Warning", "THUNDERSTORM"),
        ("Hail Advisory", "THUNDERSTORM"),
        ("Winter Storm Warning", "WINTER"),
        ("Freeze Warning", "WINTER"),
        ("Red Flag Warning", "WILDFIRE"),
        ("Extreme Heat Warning", "EXTREME_HEAT"),
        ("High Wind Warning", "WIND"),
        ("High Surf Advisory", "COASTAL"),
        ("Rip Current Statement", "COASTAL"),
        ("Dense Fog Advisory", "VISIBILITY"),
        ("Dust Storm Warning", "VISIBILITY"),
        ("Unusual Phenomenon", "OTHER"),
    ],
)
def test_classify_event_categories(event: str, expected: str) -> None:
    assert classify_event(event) == expected


# ---------------------------------------------------------------------------
# _alert scoring / centroid
# ---------------------------------------------------------------------------

def test_alert_score_uses_severity_plus_event_bonus() -> None:
    alert = _alert(_feature(event="Flood Warning", severity="Severe"))
    # Severe(82) + Flood Warning bonus(10) = 92
    assert alert.score == 92
    assert alert.category == "FLOOD"


def test_alert_defaults_when_properties_missing() -> None:
    alert = _alert({"properties": {}, "geometry": None})
    assert alert.event == "Weather Alert"
    assert alert.severity == "Unknown"
    assert alert.longitude is None
    assert alert.latitude is None


def test_centroid_none_geometry() -> None:
    assert _centroid(None) == (None, None)


def test_centroid_averages_polygon_ring() -> None:
    geometry = {"type": "Polygon", "coordinates": [[[0, 0], [2, 0], [2, 2], [0, 2], [0, 0]]]}
    lon, lat = _centroid(geometry)
    assert lon == pytest.approx(0.8)
    assert lat == pytest.approx(0.8)


def test_centroid_empty_coordinates() -> None:
    assert _centroid({"coordinates": []}) == (None, None)


# ---------------------------------------------------------------------------
# geometry containment
# ---------------------------------------------------------------------------

def test_geometry_contains_multipolygon() -> None:
    geometry = {
        "type": "MultiPolygon",
        "coordinates": [[[[0, 0], [4, 0], [4, 4], [0, 4], [0, 0]]]],
    }
    assert _geometry_contains(geometry, 2.0, 2.0)
    assert not _geometry_contains(geometry, 10.0, 10.0)


def test_geometry_contains_unsupported_type_is_false() -> None:
    assert _geometry_contains({"type": "Point", "coordinates": [2.0, 2.0]}, 2.0, 2.0) is False


def test_ring_contains_requires_three_points() -> None:
    assert _ring_contains([[0, 0], [1, 1]], 0.5, 0.5) is False
