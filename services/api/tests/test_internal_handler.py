from app.internal_handler import handler
from app.models import NationalWeatherSnapshot, Place, RoadEventFeedRegistry
from app.vrp.models import MultiStopRoutePlan, VRPSolution


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


def test_internal_handler_supports_weather_raster_png(monkeypatch) -> None:
    monkeypatch.setattr("app.internal_handler.get_weather_raster_png", lambda: b"png-bytes")

    response = handler({"method": "GET", "path": "/risk/weather-raster.png"}, None)

    assert response["content_type"] == "image/png"
    assert response["body_base64"] == "cG5nLWJ5dGVz"


def test_internal_handler_supports_road_event_feeds(monkeypatch) -> None:
    monkeypatch.setattr(
        "app.internal_handler.get_road_event_feeds",
        lambda state=None, limit=30: RoadEventFeedRegistry(
            generated_at="2026-06-11T00:00:00Z",
            source="USDOT WZDx Feed Registry",
            active_feeds=1,
            no_key_feeds=1,
            feeds=[
                {
                    "feed_id": "oklahoma:odot",
                    "state": "oklahoma",
                    "issuing_organization": "Oklahoma DOT",
                    "feed_name": "odot",
                    "format": "geojson",
                    "active": True,
                    "requires_api_key": False,
                    "endpoint_host": "oktraffic.org",
                }
            ],
            source_status={"wzdx_registry": "LIVE"},
        ),
    )

    response = handler(
        {"method": "GET", "path": "/road-events/feeds", "query": {"state": "oklahoma", "limit": "5"}},
        None,
    )

    assert response["data"]["feeds"][0]["state"] == "oklahoma"


def test_internal_handler_supports_multi_stop_route(monkeypatch) -> None:
    monkeypatch.setattr(
        "app.internal_handler.multi_stop_route_service.plan",
        lambda command: MultiStopRoutePlan(
            mode=command.mode,
            vehicle_type=command.vehicle_type,
            submitted_sequence=[stop.stop_id for stop in command.stops],
            optimized_sequence=None,
            sequence_changed=False,
            total_distance_miles=12,
            total_duration_minutes=18,
            risk_adjusted_duration_minutes=22,
            route_risk_score=20,
            legs=[],
        ),
    )

    response = handler(
        {
            "method": "POST",
            "path": "/routes/multi-stop",
            "body": {
                "mode": "MANUAL_ORDER",
                "vehicleType": "CAR",
                "stops": [
                    {"stopId": "A", "kind": "DEPOT", "name": "Atlanta", "latitude": 33.749, "longitude": -84.388},
                    {"stopId": "B", "kind": "FINAL", "name": "Macon", "latitude": 32.8407, "longitude": -83.6324},
                ],
            },
        },
        None,
    )

    assert response["data"]["submitted_sequence"] == ["A", "B"]


def test_internal_handler_supports_vrp_solve(monkeypatch) -> None:
    monkeypatch.setattr(
        "app.internal_handler.vrp_optimization_service.solve",
        lambda scenario: VRPSolution(
            solver=scenario.solver,
            status="FEASIBLE",
            objective_value=10,
            solve_time_ms=2,
            routes=[],
        ),
    )

    response = handler(
        {
            "method": "POST",
            "path": "/vrp/solve",
            "body": {
                "depot": {"name": "Atlanta depot", "location": {"latitude": 33.749, "longitude": -84.388}},
                "vehicles": [{"vehicleId": "van-1", "capacityUnits": 2, "startLocation": {"latitude": 33.749, "longitude": -84.388}}],
                "jobs": [{"jobId": "job-1", "name": "Macon", "location": {"latitude": 32.8407, "longitude": -83.6324}}],
            },
        },
        None,
    )

    assert response["data"]["status"] == "FEASIBLE"
