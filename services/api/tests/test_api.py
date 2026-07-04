import os
from pathlib import Path

os.environ["DATABASE_PATH"] = str(Path(__file__).parent / "test.db")

from fastapi.testclient import TestClient

from app.main import app
from app.models import (
    DirectionsPlan,
    LocationRisk,
    NationalRiskOverview,
    NetworkOverview,
    Place,
    RoadEventFeedRegistry,
    VehicleType,
    WeatherRisk,
)
from app.vrp.models import MultiStopRoutePlan, VRPSolution


client = TestClient(app)


def test_health() -> None:
    assert client.get("/health").json()["status"] == "healthy"


def test_list_seeded_deliveries() -> None:
    response = client.get("/deliveries")
    assert response.status_code == 200
    assert len(response.json()) >= 5


def test_duplicate_event_is_idempotent() -> None:
    delivery_id = "PD-1002"
    payload = {"event_id": "duplicate-test", "to_status": "IN_TRANSIT"}
    first = client.post(f"/deliveries/{delivery_id}/events", json=payload)
    second = client.post(f"/deliveries/{delivery_id}/events", json=payload)
    assert first.status_code == 200
    assert second.status_code == 200
    assert first.json()["version"] == second.json()["version"]


def test_invalid_transition_returns_conflict() -> None:
    response = client.post(
        "/deliveries/PD-1003/events",
        json={"event_id": "invalid-test", "to_status": "DELIVERED"},
    )
    assert response.status_code == 409


def test_network_returns_optimized_routes(monkeypatch) -> None:
    monkeypatch.setattr(
        "app.main.build_network",
        lambda deliveries, vehicle_type=None: NetworkOverview(
            generated_at="2026-06-10T00:00:00Z",
            routes=[],
            weather=[],
            algorithm="test optimizer",
            total_distance_miles=0,
            avoided_risk_minutes=0,
        ),
    )

    response = client.get("/network")

    assert response.status_code == 200
    assert response.json()["algorithm"] == "test optimizer"


def test_search_places_supports_nationwide_results(monkeypatch) -> None:
    monkeypatch.setattr(
        "app.main.search_places",
        lambda query: [
            Place(
                place_id="1",
                display_name="Space Needle, Seattle, Washington, United States",
                city="Seattle",
                state="Washington",
                latitude=47.6205,
                longitude=-122.3493,
            )
        ],
    )

    response = client.get("/places/search?q=Space%20Needle")

    assert response.status_code == 200
    assert response.json()[0]["state"] == "Washington"


def test_directions_accepts_us_places(monkeypatch) -> None:
    seattle = Place(
        place_id="1",
        display_name="Seattle, Washington, United States",
        city="Seattle",
        state="Washington",
        latitude=47.6062,
        longitude=-122.3321,
    )
    miami = Place(
        place_id="2",
        display_name="Miami, Florida, United States",
        city="Miami",
        state="Florida",
        latitude=25.7617,
        longitude=-80.1918,
    )
    monkeypatch.setattr(
        "app.main.build_directions",
        lambda command: DirectionsPlan(
            generated_at="2026-06-11T00:00:00Z",
            origin=command.origin,
            destination=command.destination,
            vehicle_type=VehicleType.CAR,
            coordinates=[[-122.3321, 47.6062], [-80.1918, 25.7617]],
            distance_miles=3300,
            duration_minutes=2880,
            climate_delay_minutes=20,
            risk_score=15,
            weather=[],
            summary="Seattle to Miami",
            alternatives=[],
        ),
    )

    response = client.post(
        "/directions",
        json={"origin": seattle.model_dump(), "destination": miami.model_dump(), "vehicle_type": "CAR"},
    )

    assert response.status_code == 200
    assert response.json()["summary"] == "Seattle to Miami"


def test_national_risk_returns_live_summary(monkeypatch) -> None:
    monkeypatch.setattr(
        "app.main.national_risk",
        lambda: NationalRiskOverview(
            generated_at="2026-06-11T00:00:00Z",
            score=72,
            level="HIGH",
            active_alerts=12,
            severe_alerts=3,
            alerts_with_geometry=8,
            alerts=[],
            by_event={"Flood Warning": 4},
        ),
    )

    response = client.get("/risk/national")

    assert response.status_code == 200
    assert response.json()["level"] == "HIGH"


def test_location_risk_scores_selected_place(monkeypatch) -> None:
    place = Place(
        place_id="miami",
        display_name="Miami, Florida, United States",
        city="Miami",
        state="Florida",
        latitude=25.7617,
        longitude=-80.1918,
    )
    monkeypatch.setattr(
        "app.main.location_risk",
        lambda selected: LocationRisk(
            generated_at="2026-06-11T00:00:00Z",
            place=selected,
            score=45,
            level="MODERATE",
            summary="Moderate near-term driving weather risk",
            factors={"active_alerts": 0, "flood": 0, "precipitation": 70, "wind": 20, "heat": 15},
            alerts=[],
            weather=WeatherRisk(
                id="weather-miami",
                city="Miami",
                latitude=25.7617,
                longitude=-80.1918,
                temperature_f=88,
                precipitation_probability=70,
                wind_speed_mph=8,
                risk_score=45,
                risk_level="ELEVATED",
            ),
        ),
    )

    response = client.post("/risk/location", json=place.model_dump())

    assert response.status_code == 200
    assert response.json()["factors"]["precipitation"] == 70


def test_road_event_feeds_exposes_wzdx_registry(monkeypatch) -> None:
    monkeypatch.setattr(
        "app.main.get_road_event_feeds",
        lambda state=None, limit=30: RoadEventFeedRegistry(
            generated_at="2026-06-11T00:00:00Z",
            source="USDOT WZDx Feed Registry",
            active_feeds=1,
            no_key_feeds=1,
            feeds=[{
                "feed_id": "utah:udot",
                "state": "utah",
                "issuing_organization": "Utah DOT",
                "feed_name": "udot",
                "format": "geojson",
                "version": "4",
                "update_frequency": "15m",
                "active": True,
                "requires_api_key": False,
                "endpoint_host": "udottraffic.utah.gov",
                "longitude": -111.88822,
                "latitude": 40.76031,
            }],
            source_status={"wzdx_registry": "LIVE"},
        ),
    )

    response = client.get("/road-events/feeds?state=utah&limit=5")

    assert response.status_code == 200
    assert response.json()["source"] == "USDOT WZDx Feed Registry"
    assert response.json()["feeds"][0]["endpoint_host"] == "udottraffic.utah.gov"


def test_multi_stop_endpoint_returns_leg_by_leg_plan(monkeypatch) -> None:
    monkeypatch.setattr(
        "app.vrp.routes.multi_stop_route_service.plan",
        lambda command: MultiStopRoutePlan(
            mode=command.mode,
            vehicle_type=command.vehicle_type,
            submitted_sequence=[stop.stop_id for stop in command.stops],
            optimized_sequence=None,
            sequence_changed=False,
            explanation=["Submitted stop order was preserved."],
            total_distance_miles=20,
            total_duration_minutes=24,
            risk_adjusted_duration_minutes=31,
            route_risk_score=35,
            legs=[
                {
                    "from_stop_id": "A",
                    "to_stop_id": "B",
                    "sequence": 1,
                    "distance_miles": 20,
                    "duration_minutes": 24,
                    "risk_adjusted_duration_minutes": 31,
                    "risk_score": 35,
                    "primary_hazard": "Rain",
                    "geometry": {"type": "LineString", "coordinates": [[-84, 33], [-83, 32]]},
                    "explanation": [],
                }
            ],
            source_status={"routing_matrix": "LIVE"},
        ),
    )

    response = client.post(
        "/routes/multi-stop",
        json={
            "mode": "MANUAL_ORDER",
            "vehicleType": "VAN",
            "stops": [
                {"stopId": "A", "kind": "DEPOT", "name": "Atlanta", "latitude": 33.749, "longitude": -84.388},
                {"stopId": "B", "kind": "FINAL", "name": "Macon", "latitude": 32.8407, "longitude": -83.6324},
            ],
        },
    )

    assert response.status_code == 200
    assert response.json()["submitted_sequence"] == ["A", "B"]
    assert response.json()["legs"][0]["risk_score"] == 35


def test_vrp_solve_endpoint_returns_vehicle_routes(monkeypatch) -> None:
    monkeypatch.setattr(
        "app.vrp.routes.vrp_optimization_service.solve",
        lambda scenario: VRPSolution(
            solver=scenario.solver,
            status="FEASIBLE",
            objective_value=100,
            solve_time_ms=5,
            routes=[{
                "vehicle_id": scenario.vehicles[0].vehicle_id,
                "vehicle_type": scenario.vehicles[0].vehicle_type,
                "stops": [{"job_id": scenario.jobs[0].job_id, "sequence": 1}],
            }],
            source_status={"solver": "LIVE"},
        ),
    )

    response = client.post(
        "/vrp/solve",
        json={
            "depot": {"name": "Atlanta depot", "location": {"latitude": 33.749, "longitude": -84.388}},
            "vehicles": [{"vehicleId": "van-1", "capacityUnits": 2, "startLocation": {"latitude": 33.749, "longitude": -84.388}}],
            "jobs": [{"jobId": "job-1", "name": "Macon", "location": {"latitude": 32.8407, "longitude": -83.6324}}],
        },
    )

    assert response.status_code == 200
    assert response.json()["routes"][0]["vehicle_id"] == "van-1"
    assert response.json()["routes"][0]["stops"][0]["job_id"] == "job-1"


def test_submit_and_get_optimization_job() -> None:
    submitted = client.post("/optimizations")
    assert submitted.status_code == 202
    job_id = submitted.json()["job_id"]

    fetched = client.get(f"/optimizations/{job_id}")
    assert fetched.status_code == 200
    assert fetched.json()["status"] == "QUEUED"
