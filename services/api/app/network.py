import json
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime
from urllib.parse import urlencode

from .models import DeliverySummary, Location, NetworkOverview, OptimizedRoute, RouteStop, VehicleType, WeatherRisk
from .resilience import resilient_urlopen
from .optimizer import solve_routes
from .vehicle_profiles import VEHICLE_PROFILES


CITY_COORDINATES: dict[str, tuple[float, float]] = {
    "Atlanta": (-84.3880, 33.7490),
    "Marietta": (-84.5507, 33.9526),
    "Decatur": (-84.2963, 33.7748),
    "Roswell": (-84.3616, 34.0232),
    "Savannah": (-81.0998, 32.0809),
    "Athens": (-83.3576, 33.9519),
    "Macon": (-83.6324, 32.8407),
    "Augusta": (-81.9748, 33.4735),
    "Columbus": (-84.9877, 32.4610),
}

CITY_STOPS: dict[str, tuple[str, float, float]] = {
    "Atlanta": ("675 Ponce De Leon Ave NE, Atlanta, GA 30308", -84.3656, 33.7725),
    "Marietta": ("10 N Park Square, Marietta, GA 30060", -84.5506, 33.9529),
    "Decatur": ("101 E Court Square, Decatur, GA 30030", -84.2964, 33.7751),
    "Roswell": ("610 Atlanta St, Roswell, GA 30075", -84.3616, 34.0187),
    "Savannah": ("2 W Bay St, Savannah, GA 31401", -81.0918, 32.0809),
    "Athens": ("100 N Jackson St, Athens, GA 30601", -83.3738, 33.9584),
    "Macon": ("700 Poplar St, Macon, GA 31201", -83.6294, 32.8368),
    "Augusta": ("601 Greene St, Augusta, GA 30901", -81.9637, 33.4707),
    "Columbus": ("100 10th St, Columbus, GA 31901", -84.9890, 32.4657),
}

DEPOT = Location(
    city="Atlanta",
    state="GA",
    address="675 Ponce De Leon Ave NE, Atlanta, GA 30308",
    longitude=-84.3656,
    latitude=33.7725,
)

ROUTE_COLORS = ["#29e184", "#ff9f43", "#6ea8fe", "#ec5b69"]


def build_network(
    deliveries: list, preferred_vehicle_type: VehicleType | None = None
) -> NetworkOverview:
    active = [
        delivery
        for delivery in deliveries
        if delivery.status not in ("DELIVERED", "FAILED", "CANCELLED")
    ]
    weather_cities = sorted(
        {delivery.destination.city for delivery in active} | {"Atlanta"}
    )
    with ThreadPoolExecutor(max_workers=6) as executor:
        weather = list(executor.map(fetch_weather, weather_cities))

    weather_by_city = {item.city: item for item in weather}
    optimized = solve_routes(
        active, weather_by_city, CITY_COORDINATES, preferred_vehicle_type
    )

    routes: list[OptimizedRoute] = []
    for index, vehicle_route in enumerate(optimized):
        driver_id = vehicle_route.driver_id
        vehicle_type = vehicle_route.vehicle_type
        ordered = vehicle_route.deliveries
        waypoints = [location_coordinates(DEPOT)]
        waypoints.extend(location_coordinates(job.destination) for job in ordered)
        geometry, distance, base_duration = fetch_route(waypoints)
        duration = base_duration * VEHICLE_PROFILES[vehicle_type]["duration"]
        average_risk = round(
            sum(weather_by_city[job.destination.city].risk_score for job in ordered)
            / len(ordered)
        )
        climate_delay = round(duration * (average_risk / 100) * 0.35, 1)
        routes.append(
            OptimizedRoute(
                route_id=f"OPT-{index + 1:02}",
                driver_id=driver_id,
                vehicle_type=vehicle_type,
                color=ROUTE_COLORS[index % len(ROUTE_COLORS)],
                delivery_ids=[job.delivery_id for job in ordered],
                coordinates=geometry,
                distance_miles=round(distance, 1),
                duration_minutes=round(duration, 1),
                climate_delay_minutes=climate_delay,
                risk_score=average_risk,
                optimization_note=(
                    "Low-risk stops prioritized before the forecast window"
                    if average_risk >= 35
                    else "Balanced assignment, promised time, and road distance"
                ),
                stops=[
                    route_stop(sequence, delivery, weather_by_city)
                    for sequence, delivery in enumerate(ordered, start=1)
                ],
            )
        )
    return NetworkOverview(
        generated_at=datetime.now(UTC),
        routes=routes,
        weather=weather,
        algorithm="OR-Tools climate-aware capacitated VRP v1",
        total_distance_miles=round(sum(route.distance_miles for route in routes), 1),
        avoided_risk_minutes=round(
            sum(route.climate_delay_minutes for route in routes) * 0.42, 1
        ),
    )


def location_coordinates(location: Location) -> tuple[float, float]:
    if location.longitude is not None and location.latitude is not None:
        return location.longitude, location.latitude
    _, longitude, latitude = CITY_STOPS[location.city]
    return longitude, latitude


def location_address(location: Location) -> str:
    if location.address:
        return location.address
    return CITY_STOPS[location.city][0]


def route_stop(
    sequence: int,
    delivery: DeliverySummary,
    weather_by_city: dict[str, WeatherRisk],
) -> RouteStop:
    longitude, latitude = location_coordinates(delivery.destination)
    return RouteStop(
        sequence=sequence,
        delivery_id=delivery.delivery_id,
        address=location_address(delivery.destination),
        city=delivery.destination.city,
        state=delivery.destination.state,
        longitude=longitude,
        latitude=latitude,
        promised_at=delivery.promised_at,
        risk_score=weather_by_city[delivery.destination.city].risk_score,
    )


def fetch_weather(city: str) -> WeatherRisk:
    longitude, latitude = CITY_COORDINATES[city]
    query = urlencode(
        {
            "latitude": latitude,
            "longitude": longitude,
            "current": "temperature_2m,precipitation,wind_speed_10m",
            "hourly": "precipitation_probability",
            "temperature_unit": "fahrenheit",
            "wind_speed_unit": "mph",
            "forecast_days": 1,
        }
    )
    try:
        with resilient_urlopen("open-meteo", f"https://api.open-meteo.com/v1/forecast?{query}", timeout=5) as response:
            data = json.load(response)
        current = data["current"]
        precipitation_probability = max(data["hourly"]["precipitation_probability"][:6])
        temperature = float(current["temperature_2m"])
        wind = float(current["wind_speed_10m"])
        risk = min(
            100,
            round(
                precipitation_probability * 0.6
                + wind * 1.4
                + max(0, temperature - 92) * 2
            ),
        )
    except Exception:
        temperature, precipitation_probability, wind = 0, 0, 0
        risk = 50
    return WeatherRisk(
        id=f"weather-{city.lower()}",
        city=city,
        latitude=latitude,
        longitude=longitude,
        temperature_f=temperature,
        precipitation_probability=precipitation_probability,
        wind_speed_mph=wind,
        risk_score=risk,
        risk_level=(
            "UNKNOWN"
            if temperature == 0 and precipitation_probability == 0 and wind == 0
            else "HIGH" if risk >= 60 else "ELEVATED" if risk >= 35 else "LOW"
        ),
        data_status=(
            "UNAVAILABLE"
            if temperature == 0 and precipitation_probability == 0 and wind == 0
            else "LIVE"
        ),
    )


def fetch_route(waypoints: list[tuple[float, float]]) -> tuple[list[list[float]], float, float]:
    coordinate_string = ";".join(f"{lon},{lat}" for lon, lat in waypoints)
    url = (
        f"https://router.project-osrm.org/route/v1/driving/{coordinate_string}"
        "?overview=full&geometries=geojson&steps=false"
    )
    try:
        with resilient_urlopen("osrm", url, timeout=8) as response:
            route = json.load(response)["routes"][0]
        return (
            route["geometry"]["coordinates"],
            route["distance"] / 1609.344,
            route["duration"] / 60,
        )
    except Exception:
        coordinates = [[lon, lat] for lon, lat in waypoints]
        distance = sum(
            ((coordinates[i][0] - coordinates[i - 1][0]) ** 2 + (coordinates[i][1] - coordinates[i - 1][1]) ** 2)
            ** 0.5
            * 55
            for i in range(1, len(coordinates))
        )
        return coordinates, distance, distance * 1.25
