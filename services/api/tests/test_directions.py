from app.directions import (
    _label_alternatives,
    _sample_geometry,
    _search_open_meteo_places,
    _weather_from_payload,
)
from app.models import RouteAlternative


def alternative(identifier: str, minutes: float, risk: int) -> RouteAlternative:
    return RouteAlternative(
        alternative_id=identifier,
        label="Alternative",
        coordinates=[[-80.0, 25.0], [-80.1, 26.0]],
        distance_miles=50,
        duration_minutes=minutes,
        climate_delay_minutes=5,
        risk_score=risk,
        weather=[],
        hazards=[],
    )


def test_samples_short_and_long_routes() -> None:
    short = [[-80.0, 25.0], [-80.1, 25.1], [-80.2, 25.2]]
    long = [[float(index), float(index)] for index in range(100)]
    assert len(_sample_geometry(short, 8)) == 3
    assert len(_sample_geometry(long, 8)) == 8


def test_labels_real_candidates_by_time_and_risk() -> None:
    routes = [alternative("fast", 60, 80), alternative("safe", 75, 20), alternative("mid", 68, 45)]
    _label_alternatives(routes)
    assert routes[0].label == "Fastest"
    assert routes[1].label == "Lower weather risk"
    assert routes[2].label == "Balanced"


def test_builds_weather_risk_from_live_provider_shape() -> None:
    weather = _weather_from_payload(
        "Route sample 1",
        25.7,
        -80.1,
        {
            "current": {"temperature_2m": 90, "wind_speed_10m": 10},
            "hourly": {"precipitation_probability": [10, 20, 30, 40, 20, 10]},
        },
    )
    assert weather.data_status == "LIVE"
    assert weather.risk_score == 38


def test_open_meteo_place_fallback(monkeypatch) -> None:
    class Response:
        def __enter__(self):
            return self

        def __exit__(self, *args):
            return None

        def read(self):
            return (
                b'{"results":[{"id":1,"name":"Miami Beach","admin1":"Florida",'
                b'"country":"United States","latitude":25.79,"longitude":-80.13}]}'
            )

    monkeypatch.setattr("app.directions.urlopen", lambda *args, **kwargs: Response())

    places = _search_open_meteo_places("Miami Beach")

    assert places[0].city == "Miami Beach"
    assert places[0].state == "Florida"
