"""Network-mocked tests for app.directions.

The outbound HTTP layer is replaced with fixture payloads so the parsing,
scoring, and decision logic can be exercised deterministically.
"""
from __future__ import annotations

import json

import pytest

from app.directions import (
    _build_route_decision,
    _format_duration,
    _hazard_label,
    _place,
    _primary_hazard,
    _score_candidate,
    _severity,
    fetch_coordinate_weather,
    fetch_route_alternatives,
    fetch_route_weather,
    search_places,
)
from app.models import DirectionsRequest, Place, RiskAlert, RouteAlternative, WeatherRisk


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


def _raise(*args, **kwargs):
    raise RuntimeError("provider unavailable")


def _place_obj(city: str, latitude: float, longitude: float) -> Place:
    return Place(
        place_id=f"p-{city}",
        display_name=f"{city}, GA",
        city=city,
        state="GA",
        latitude=latitude,
        longitude=longitude,
    )


def _weather(risk: int = 40, precip: int = 30, wind: int = 10, temp: int = 80, status: str = "LIVE") -> WeatherRisk:
    return WeatherRisk(
        id="w",
        city="sample",
        latitude=33.0,
        longitude=-84.0,
        temperature_f=temp,
        precipitation_probability=precip,
        wind_speed_mph=wind,
        risk_score=risk,
        risk_level="ELEVATED",
        data_status=status,
    )


# ---------------------------------------------------------------------------
# search_places / _place
# ---------------------------------------------------------------------------

def test_search_places_uses_nominatim_when_available(monkeypatch) -> None:
    payload = [
        {
            "place_id": 123,
            "display_name": "Atlanta, Georgia, USA",
            "lat": "33.749",
            "lon": "-84.388",
            "address": {"city": "Atlanta", "state": "Georgia"},
        }
    ]
    monkeypatch.setattr("app.directions.urlopen", lambda *a, **k: _json_response(payload))

    places = search_places("Atlanta")

    assert places[0].city == "Atlanta"
    assert places[0].state == "Georgia"
    assert places[0].latitude == pytest.approx(33.749)


def test_search_places_falls_back_to_open_meteo(monkeypatch) -> None:
    def fake_urlopen(request, timeout=None):
        url = request.full_url if hasattr(request, "full_url") else request
        if "nominatim" in url:
            raise RuntimeError("nominatim down")
        return _json_response(
            {
                "results": [
                    {
                        "id": 9,
                        "name": "Macon",
                        "admin1": "Georgia",
                        "country": "United States",
                        "latitude": 32.84,
                        "longitude": -83.63,
                    }
                ]
            }
        )

    monkeypatch.setattr("app.directions.urlopen", fake_urlopen)

    places = search_places("Macon")

    assert places[0].place_id == "open-meteo-9"
    assert places[0].city == "Macon"


def test_place_prefers_town_when_city_missing() -> None:
    item = {
        "place_id": 5,
        "display_name": "Savannah",
        "lat": "32.08",
        "lon": "-81.09",
        "address": {"town": "Savannah", "state": "Georgia"},
    }
    assert _place(item).city == "Savannah"


# ---------------------------------------------------------------------------
# fetch_route_alternatives
# ---------------------------------------------------------------------------

def test_fetch_route_alternatives_parses_osrm(monkeypatch) -> None:
    payload = {
        "routes": [
            {"geometry": {"coordinates": [[-84.0, 33.0], [-81.0, 32.0]]}, "distance": 160934.4, "duration": 3600}
        ]
    }
    monkeypatch.setattr("app.routing.osrm.urlopen", lambda *a, **k: _json_response(payload))

    routes = fetch_route_alternatives([(-84.0, 33.0), (-81.0, 32.0)])

    assert len(routes) == 1
    coords, miles, minutes = routes[0]
    assert coords == [[-84.0, 33.0], [-81.0, 32.0]]
    assert miles == pytest.approx(100.0)
    assert minutes == pytest.approx(60.0)


def test_fetch_route_alternatives_raises_when_no_routes(monkeypatch) -> None:
    monkeypatch.setattr("app.routing.osrm.urlopen", lambda *a, **k: _json_response({"routes": []}))

    with pytest.raises(RuntimeError, match="no routes"):
        fetch_route_alternatives([(-84.0, 33.0), (-81.0, 32.0)])


# ---------------------------------------------------------------------------
# fetch_coordinate_weather
# ---------------------------------------------------------------------------

def test_fetch_coordinate_weather_success(monkeypatch) -> None:
    payload = {
        "current": {"temperature_2m": 90, "wind_speed_10m": 10},
        "hourly": {"precipitation_probability": [10, 20, 30, 40, 20, 10]},
    }
    monkeypatch.setattr("app.directions.urlopen", lambda *a, **k: _json_response(payload))

    weather = fetch_coordinate_weather("Atlanta", 33.749, -84.388)

    assert weather.data_status == "LIVE"
    assert weather.risk_score == 38  # 40*0.6 + 10*1.4
    assert weather.risk_level == "ELEVATED"


def test_fetch_coordinate_weather_fallback_on_error(monkeypatch) -> None:
    monkeypatch.setattr("app.directions.urlopen", _raise)

    weather = fetch_coordinate_weather("Atlanta", 33.749, -84.388)

    assert weather.data_status == "UNAVAILABLE"
    assert weather.risk_level == "UNKNOWN"
    assert weather.risk_score == 50


# ---------------------------------------------------------------------------
# fetch_route_weather
# ---------------------------------------------------------------------------

def test_fetch_route_weather_parses_batch(monkeypatch) -> None:
    samples = [(0, -84.0, 33.0), (1, -81.0, 32.0)]
    payload = [
        {"current": {"temperature_2m": 80, "wind_speed_10m": 5}, "hourly": {"precipitation_probability": [10] * 6}},
        {"current": {"temperature_2m": 70, "wind_speed_10m": 8}, "hourly": {"precipitation_probability": [20] * 6}},
    ]
    monkeypatch.setattr("app.directions.urlopen", lambda *a, **k: _json_response(payload))

    result = fetch_route_weather(samples)

    assert len(result) == 2
    assert all(item.data_status == "LIVE" for item in result)


def test_fetch_route_weather_rejects_sample_count_mismatch(monkeypatch) -> None:
    samples = [(0, -84.0, 33.0), (1, -81.0, 32.0)]
    # Only one result for two samples -> ValueError -> fallback path.
    payload = [{"current": {"temperature_2m": 80, "wind_speed_10m": 5}, "hourly": {"precipitation_probability": [10] * 6}}]
    monkeypatch.setattr("app.directions.urlopen", lambda *a, **k: _json_response(payload))
    monkeypatch.setattr("app.directions.nearest_snapshot_weather", lambda lat, lon: None)

    result = fetch_route_weather(samples)

    assert len(result) == 2
    assert all(item.data_status == "UNAVAILABLE" for item in result)


def test_fetch_route_weather_uses_snapshot_when_available(monkeypatch) -> None:
    samples = [(0, -84.0, 33.0)]
    snapshot_weather = _weather(risk=22, status="LIVE")
    monkeypatch.setattr("app.directions.urlopen", _raise)
    monkeypatch.setattr("app.directions.nearest_snapshot_weather", lambda lat, lon: snapshot_weather)

    result = fetch_route_weather(samples)

    assert result[0] is snapshot_weather


# ---------------------------------------------------------------------------
# _score_candidate
# ---------------------------------------------------------------------------

def test_score_candidate_combines_weather_and_alerts(monkeypatch) -> None:
    geometry = [[-84.0, 33.0], [-83.0, 32.7], [-82.0, 32.4], [-81.0, 32.0]]
    alert = RiskAlert(
        alert_id="flood-1",
        event="Flood Warning",
        severity="Severe",
        urgency="Immediate",
        certainty="Observed",
        headline="Flooding",
        area="GA",
        score=92,
        category="FLOOD",
    )
    monkeypatch.setattr(
        "app.directions.fetch_route_weather",
        lambda samples: [_weather(risk=40) for _ in samples],
    )
    monkeypatch.setattr(
        "app.directions.alerts_for_route_samples",
        lambda samples: ([[alert], [], [], []], "LIVE"),
    )
    command = DirectionsRequest(origin=_place_obj("Atlanta", 33.749, -84.388), destination=_place_obj("Savannah", 32.08, -81.09))

    alternative = _score_candidate(0, (geometry, 100.0, 60.0), command)

    assert alternative.alternative_id == "route-1"
    assert alternative.risk_score == 92  # max(alert 92, weather 40)
    assert alternative.hazards[0].category == "FLOOD"
    assert alternative.confidence == "HIGH"
    assert alternative.source_status["routing"] == "LIVE"
    assert alternative.source_status["weather"] == "LIVE"
    assert alternative.source_status["nws_alerts"] == "LIVE"


def test_score_candidate_without_live_data_is_unavailable(monkeypatch) -> None:
    geometry = [[-84.0, 33.0], [-81.0, 32.0]]
    monkeypatch.setattr(
        "app.directions.fetch_route_weather",
        lambda samples: [_weather(risk=50, status="UNAVAILABLE") for _ in samples],
    )
    monkeypatch.setattr("app.directions.alerts_for_route_samples", lambda samples: ([[] for _ in samples], "UNAVAILABLE"))
    command = DirectionsRequest(origin=_place_obj("A", 33.0, -84.0), destination=_place_obj("B", 32.0, -81.0))

    alternative = _score_candidate(0, (geometry, 50.0, 30.0), command)

    # No live weather -> weather fallback of 50 drives the risk score.
    assert alternative.risk_score == 50
    assert alternative.source_status["weather"] == "UNAVAILABLE"
    assert alternative.source_status["nws_alerts"] == "UNAVAILABLE"


# ---------------------------------------------------------------------------
# _build_route_decision branches
# ---------------------------------------------------------------------------

def _alt(identifier: str, minutes: float, risk: int, label: str = "Alternative") -> RouteAlternative:
    return RouteAlternative(
        alternative_id=identifier,
        label=label,
        coordinates=[[-80.0, 25.0]],
        distance_miles=50,
        duration_minutes=minutes,
        climate_delay_minutes=0,
        risk_score=risk,
        weather=[],
        hazards=[],
    )


def test_route_decision_delays_when_all_routes_risky() -> None:
    routes = [_alt("a", 60, 80), _alt("b", 70, 65)]
    decision = _build_route_decision(routes)
    assert decision is not None
    assert decision.action == "DELAY_DEPARTURE"


def test_route_decision_takes_fastest_when_safe() -> None:
    routes = [_alt("a", 60, 20), _alt("b", 70, 25)]
    decision = _build_route_decision(routes)
    assert decision is not None
    assert decision.action == "TAKE_FASTEST"
    assert decision.recommended_alternative_id == "a"


def test_route_decision_takes_balanced_for_modest_tradeoff() -> None:
    routes = [
        _alt("fast", 100, 50, label="Fastest"),
        _alt("balanced", 103, 38, label="Balanced"),
    ]
    decision = _build_route_decision(routes)
    assert decision is not None
    assert decision.action == "TAKE_BALANCED"
    assert decision.recommended_alternative_id == "balanced"


def test_route_decision_defaults_to_fastest_when_no_clear_winner() -> None:
    routes = [_alt("fast", 100, 45), _alt("other", 120, 42)]
    decision = _build_route_decision(routes)
    assert decision is not None
    assert decision.action == "TAKE_FASTEST"


def test_route_decision_none_for_empty() -> None:
    assert _build_route_decision([]) is None


# ---------------------------------------------------------------------------
# hazard helpers
# ---------------------------------------------------------------------------

def test_primary_hazard_selects_dominant_signal() -> None:
    wind = [_weather(risk=10, precip=0, wind=40)]
    heat = [_weather(risk=10, precip=0, wind=0, temp=105)]
    alert = [_weather(risk=90, precip=0, wind=0)]
    assert _primary_hazard(wind) == "WIND"
    assert _primary_hazard(heat) == "HEAT"
    assert _primary_hazard(alert) == "ALERT"


def test_primary_hazard_unknown_when_no_signal() -> None:
    assert _primary_hazard([_weather(risk=0, precip=0, wind=0, temp=70)]) == "UNKNOWN"


def test_hazard_label_falls_back_for_unknown() -> None:
    assert _hazard_label("WIND") == "crosswinds"
    assert _hazard_label("SOMETHING_ELSE") == "limited live coverage"


def test_severity_thresholds() -> None:
    assert _severity(85) == "SEVERE"
    assert _severity(60) == "HIGH"
    assert _severity(40) == "MODERATE"
    assert _severity(10) == "LOW"


def test_format_duration_variants() -> None:
    assert _format_duration(45) == "45 min"
    assert _format_duration(60) == "1 hr"
    assert _format_duration(90) == "1 hr 30 min"
