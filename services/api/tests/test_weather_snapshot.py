from app.models import NationalWeatherSnapshot, WeatherRisk
from app.weather_collector import handler
from app.weather_snapshot import INTEREST_POINTS, fetch_nws_weather, nearest_snapshot_weather, weather_risk_score
from app.weather_raster import render_weather_raster


def test_builds_nws_weather_point(monkeypatch) -> None:
    def fake_get_json(url: str) -> dict:
        if "/points/" in url:
            return {"properties": {"forecastHourly": "https://forecast.test/hourly"}}
        return {
            "properties": {
                "periods": [
                    {
                        "temperature": 88,
                        "windSpeed": "12 to 20 mph",
                        "probabilityOfPrecipitation": {"value": 70},
                        "shortForecast": "Thunderstorms",
                    }
                ]
                * 6
            }
        }

    monkeypatch.setattr("app.weather_snapshot._get_json", fake_get_json)

    point = fetch_nws_weather("miami-i95", "Miami / I-95", 25.76, -80.19)

    assert point.data_status == "LIVE"
    assert point.source == "NOAA / National Weather Service"
    assert point.wind_speed_mph == 20
    assert point.risk_score >= 60


def test_weather_risk_score_increases_for_severe_forecast() -> None:
    normal = weather_risk_score(75, 20, 5, ["Partly Cloudy"])
    severe = weather_risk_score(75, 20, 5, ["Severe Thunderstorms"])
    assert severe > normal


def test_weather_risk_score_increases_for_black_ice_conditions() -> None:
    dry_freezing = weather_risk_score(30, 0, 8, ["Mostly Cloudy"])
    freezing_rain = weather_risk_score(30, 45, 18, ["Freezing Rain"])
    assert freezing_rain >= dry_freezing + 35


def test_nearest_snapshot_weather_uses_cached_point(monkeypatch) -> None:
    point = WeatherRisk(
        id="atlanta",
        city="Atlanta / I-85",
        latitude=33.749,
        longitude=-84.388,
        temperature_f=80,
        precipitation_probability=30,
        wind_speed_mph=10,
        risk_score=26,
        risk_level="LOW",
        source="NOAA / National Weather Service",
    )
    snapshot = NationalWeatherSnapshot(
        generated_at="2026-06-11T00:00:00Z",
        expires_at="2026-06-11T01:15:00Z",
        coverage=1,
        points=[point],
    )
    monkeypatch.setattr("app.weather_snapshot.get_weather_snapshot", lambda refresh_if_missing=False: snapshot)

    result = nearest_snapshot_weather(33.8, -84.4)

    assert result
    assert result.source == "NOAA / National Weather Service"
    assert result.city.startswith("NOAA snapshot near")


def test_collector_saves_generated_snapshot(monkeypatch) -> None:
    snapshot = NationalWeatherSnapshot(
        generated_at="2026-06-11T00:00:00Z",
        expires_at="2026-06-11T01:15:00Z",
        coverage=0.8,
        points=[],
        source_status={"nws_forecast": "PARTIAL"},
    )
    saved = []
    monkeypatch.setattr("app.weather_collector.build_weather_snapshot", lambda: snapshot)
    monkeypatch.setattr("app.weather_collector.save_weather_snapshot", saved.append)

    response = handler({}, None)

    assert saved == [snapshot]
    assert response["coverage"] == 0.8


def test_interest_grid_is_large_enough_for_national_monitoring() -> None:
    assert len(INTEREST_POINTS) >= 150


def test_interest_grid_uses_production_corridor_labels() -> None:
    labels = [point[1] for point in INTEREST_POINTS]
    assert all("sample" not in label.lower() for label in labels)
    assert any(label.startswith("I-35 near") for label in labels)
    assert any(" to " in label for label in labels)


def test_renders_national_png() -> None:
    point = WeatherRisk(
        id="atlanta",
        city="Atlanta",
        latitude=33.749,
        longitude=-84.388,
        temperature_f=80,
        precipitation_probability=30,
        wind_speed_mph=10,
        risk_score=60,
        risk_level="HIGH",
    )
    snapshot = NationalWeatherSnapshot(
        generated_at="2026-06-11T00:00:00Z",
        expires_at="2026-06-11T01:15:00Z",
        coverage=1,
        points=[point],
    )

    png = render_weather_raster(snapshot)

    assert png.startswith(b"\x89PNG")
