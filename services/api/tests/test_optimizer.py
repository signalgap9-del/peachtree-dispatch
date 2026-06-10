from datetime import UTC, datetime, timedelta

from app.models import DeliveryStatus, DeliverySummary, Location, WeatherRisk
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
