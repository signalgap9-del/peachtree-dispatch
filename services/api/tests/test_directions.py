from app.directions import (
    _build_route_decision,
    _build_route_segments,
    _label_alternatives,
    _sample_geometry,
    _search_open_meteo_places,
    _weather_from_payload,
)
from app.models import RouteAlternative, WeatherRisk


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


def test_build_route_decision_recommends_lower_risk_when_tradeoff_is_worth_it() -> None:
    routes = [
        alternative("fast", 100, 72),
        alternative("safe", 107, 38),
        alternative("balanced", 104, 54),
    ]
    routes[0].label = "Fastest"
    routes[1].label = "Lower weather risk"
    routes[2].label = "Balanced"

    decision = _build_route_decision(routes)

    assert decision is not None
    assert decision.action == "TAKE_LOWER_RISK"
    assert decision.recommended_alternative_id == "safe"
    assert decision.risk_delta == 34
    assert decision.time_delta_minutes == 7


def test_build_route_segments_groups_weather_samples_by_hazard() -> None:
    weather = [
        WeatherRisk(
            id="rain",
            city="Miami, FL",
            latitude=25.7,
            longitude=-80.1,
            temperature_f=84,
            precipitation_probability=82,
            wind_speed_mph=18,
            risk_score=76,
            risk_level="HIGH",
        ),
        WeatherRisk(
            id="wind",
            city="Savannah, GA",
            latitude=32.0,
            longitude=-81.1,
            temperature_f=78,
            precipitation_probability=20,
            wind_speed_mph=29,
            risk_score=61,
            risk_level="HIGH",
        ),
    ]

    segments = _build_route_segments(weather)

    assert segments[0].primary_hazard == "FLOOD"
    assert segments[0].severity == "HIGH"
    assert segments[1].primary_hazard == "WIND"


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
