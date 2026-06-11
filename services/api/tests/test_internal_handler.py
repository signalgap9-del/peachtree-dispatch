from app.internal_handler import handler
from app.models import NationalWeatherSnapshot, Place


def test_internal_handler_dispatches_place_search(monkeypatch) -> None:
    monkeypatch.setattr(
        "app.internal_handler.search_places",
        lambda query: [
            Place(
                place_id="wpb",
                display_name="West Palm Beach, Florida, United States",
                city="West Palm Beach",
                state="Florida",
                latitude=26.7153,
                longitude=-80.0534,
            )
        ],
    )

    response = handler(
        {"method": "GET", "path": "/places/search", "query": {"q": "West Palm Beach"}},
        None,
    )

    assert response["data"][0]["city"] == "West Palm Beach"


def test_internal_handler_supports_network() -> None:
    response = handler(
        {"method": "GET", "path": "/network", "query": {"vehicle_type": "CAR"}},
        None,
    )

    assert response["data"]["routes"]
    assert all(route["vehicle_type"] == "CAR" for route in response["data"]["routes"])


def test_internal_handler_supports_weather_snapshot(monkeypatch) -> None:
    monkeypatch.setattr(
        "app.internal_handler.get_weather_snapshot",
        lambda: NationalWeatherSnapshot(
            generated_at="2026-06-11T00:00:00Z",
            expires_at="2026-06-11T01:15:00Z",
            coverage=1,
            points=[],
        ),
    )

    response = handler({"method": "GET", "path": "/risk/weather-snapshot"}, None)

    assert response["data"]["coverage"] == 1
