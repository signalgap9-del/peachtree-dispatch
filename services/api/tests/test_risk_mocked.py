"""Network-mocked tests for app.risk scoring and aggregation."""
from __future__ import annotations

import pytest

from app.models import Place, RiskAlert, WeatherRisk
from app.risk import _summary, location_risk, national_risk, risk_level


def _alert(alert_id: str, event: str, score: int, category: str, geometry=None) -> RiskAlert:
    return RiskAlert(
        alert_id=alert_id,
        event=event,
        severity="Severe",
        urgency="Immediate",
        certainty="Observed",
        headline=f"{event} headline",
        area="GA",
        score=score,
        category=category,
        geometry=geometry,
    )


def _weather(wind: int = 10, temp: int = 80, precip: int = 30) -> WeatherRisk:
    return WeatherRisk(
        id="w",
        city="Atlanta",
        latitude=33.7,
        longitude=-84.3,
        temperature_f=temp,
        precipitation_probability=precip,
        wind_speed_mph=wind,
        risk_score=40,
        risk_level="ELEVATED",
        data_status="LIVE",
    )


@pytest.fixture(autouse=True)
def _clear_national_cache(monkeypatch):
    monkeypatch.setattr("app.risk._NATIONAL_CACHE", None)


def test_national_risk_aggregates_alert_scores(monkeypatch) -> None:
    alerts = [
        _alert("a", "Flood Warning", 92, "FLOOD", geometry={"type": "Point", "coordinates": [-84, 33]}),
        _alert("b", "High Wind Warning", 58, "WIND"),
    ]
    monkeypatch.setattr("app.risk.national_alerts_result", lambda: (alerts, "LIVE"))

    overview = national_risk()

    assert overview.active_alerts == 2
    assert overview.severe_alerts == 1  # only score >= 75
    assert overview.alerts_with_geometry == 1
    assert overview.score == 75  # round((92 + 58) / 2)
    assert overview.level == "HIGH"
    assert overview.by_event["Flood Warning"] == 1
    assert overview.source_status == {"nws_alerts": "LIVE"}


def test_national_risk_no_alerts_scores_zero(monkeypatch) -> None:
    monkeypatch.setattr("app.risk.national_alerts_result", lambda: ([], "UNAVAILABLE"))

    overview = national_risk()

    assert overview.score == 0
    assert overview.level == "LOW"
    assert overview.active_alerts == 0


def test_national_risk_is_cached(monkeypatch) -> None:
    calls = 0

    def fake():
        nonlocal calls
        calls += 1
        return ([], "LIVE")

    monkeypatch.setattr("app.risk.national_alerts_result", fake)

    national_risk()
    national_risk()

    assert calls == 1


def test_location_risk_combines_alerts_and_weather(monkeypatch) -> None:
    place = Place(
        place_id="p",
        display_name="Atlanta, GA",
        city="Atlanta",
        state="GA",
        latitude=33.7,
        longitude=-84.3,
    )
    alert = _alert("f", "Flood Warning", 92, "FLOOD")
    monkeypatch.setattr("app.risk.alerts_for_point_result", lambda lat, lon: ([alert], "LIVE"))
    monkeypatch.setattr("app.risk.fetch_coordinate_weather", lambda label, lat, lon: _weather())

    result = location_risk(place)

    assert result.score == 92  # alert score dominates the weighted weather blend
    assert result.level == "SEVERE"
    assert result.factors["flood"] == 92
    assert result.factors["precipitation"] == 30
    # The "wind" factor key is overwritten by the WIND alert-category score (0 here);
    # the weather-derived wind signal is still visible on the weather payload.
    assert result.factors["wind"] == 0
    assert result.weather.wind_speed_mph == 10
    assert result.source_status == {"nws_alerts": "LIVE", "weather": "LIVE"}
    assert "1 active NWS alert" in result.summary


def test_location_risk_without_alerts_uses_weather(monkeypatch) -> None:
    place = Place(place_id="p", display_name="Miami, FL", city="Miami", state="FL", latitude=25.7, longitude=-80.1)
    monkeypatch.setattr("app.risk.alerts_for_point_result", lambda lat, lon: ([], "UNAVAILABLE"))
    # Hot, windy, wet weather should drive a non-zero score even with no alerts.
    monkeypatch.setattr(
        "app.risk.fetch_coordinate_weather",
        lambda label, lat, lon: _weather(wind=30, temp=100, precip=80),
    )

    result = location_risk(place)

    assert result.score > 0
    assert result.factors["active_alerts"] == 0
    assert "no active NWS alerts" in result.summary


def test_risk_level_thresholds() -> None:
    assert risk_level(80) == "SEVERE"
    assert risk_level(79) == "HIGH"
    assert risk_level(55) == "HIGH"
    assert risk_level(54) == "MODERATE"
    assert risk_level(30) == "MODERATE"
    assert risk_level(29) == "LOW"


def test_summary_with_and_without_alerts() -> None:
    alert = _alert("f", "Flood Warning", 92, "FLOOD")
    assert "2 active NWS alert" in _summary(90, [alert, alert])
    assert "no active NWS alerts" in _summary(10, [])
