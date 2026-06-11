from datetime import UTC, datetime, timedelta

from app.models import DeliveryStatus, DeliverySummary, Location, VehicleType, WeatherRisk
from app.network import CITY_COORDINATES
from app.optimizer import solve_routes


def test_solver_keeps_preassigned_driver_and_assigns_unassigned_stop() -> None:
    deliveries = [
        delivery("PD-1", "Athens", "driver-17"),
        delivery("PD-2", "Macon", None),
        delivery("PD-3", "Savannah", "driver-42"),
    ]
    weather = {
        city: WeatherRisk(
            id=f"weather-{city}",
            city=city,
            latitude=CITY_COORDINATES[city][1],
            longitude=CITY_COORDINATES[city][0],
            temperature_f=80,
            precipitation_probability=10,
            wind_speed_mph=5,
            risk_score=10,
            risk_level="LOW",
        )
        for city in ("Atlanta", "Athens", "Macon", "Savannah")
    }

    routes = solve_routes(deliveries, weather, CITY_COORDINATES)

    assignments = {
        job.delivery_id: route.driver_id for route in routes for job in route.deliveries
    }
    assert assignments["PD-1"] == "driver-17"
    assert assignments["PD-3"] == "driver-42"
    assert assignments["PD-2"] in {"driver-17", "driver-42"}


def test_solver_applies_requested_vehicle_profile() -> None:
    deliveries = [delivery("PD-1", "Athens", "driver-17")]
    weather = {
        city: WeatherRisk(
            id=f"weather-{city}",
            city=city,
            latitude=CITY_COORDINATES[city][1],
            longitude=CITY_COORDINATES[city][0],
            temperature_f=80,
            precipitation_probability=10,
            wind_speed_mph=5,
            risk_score=10,
            risk_level="LOW",
        )
        for city in ("Atlanta", "Athens")
    }

    routes = solve_routes(
        deliveries, weather, CITY_COORDINATES, preferred_vehicle_type=VehicleType.TRUCK
    )

    assert routes[0].vehicle_type == VehicleType.TRUCK


def test_solver_accepts_precomputed_risk_adjusted_matrix() -> None:
    deliveries = [
        delivery("PD-1", "Savannah", "driver-17"),
        delivery("PD-2", "Athens", "driver-17"),
    ]
    weather = {
        city: WeatherRisk(
            id=f"weather-{city}",
            city=city,
            latitude=CITY_COORDINATES[city][1],
            longitude=CITY_COORDINATES[city][0],
            temperature_f=80,
            precipitation_probability=10,
            wind_speed_mph=5,
            risk_score=10,
            risk_level="LOW",
        )
        for city in ("Atlanta", "Athens", "Savannah")
    }
    # Generalized cost makes Atlanta -> Athens -> Savannah the safest tour.
    matrix = [
        [0, 1000, 1],
        [1, 0, 1000],
        [1000, 1, 0],
    ]

    routes = solve_routes(
        deliveries,
        weather,
        CITY_COORDINATES,
        risk_adjusted_matrix=matrix,
    )

    assert [item.delivery_id for item in routes[0].deliveries] == ["PD-2", "PD-1"]


def delivery(delivery_id: str, city: str, driver_id: str | None) -> DeliverySummary:
    now = datetime.now(UTC)
    return DeliverySummary(
        delivery_id=delivery_id,
        status=DeliveryStatus.ASSIGNED if driver_id else DeliveryStatus.CREATED,
        driver_id=driver_id,
        origin=Location(city="Atlanta", state="GA"),
        destination=Location(city=city, state="GA"),
        promised_at=now + timedelta(hours=4),
        updated_at=now,
        version=1,
    )
