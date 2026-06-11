import json
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from .hazards import USER_AGENT, alerts_for_point_result
from .models import (
    DirectionsPlan,
    DirectionsRequest,
    HazardExposure,
    Place,
    RouteAlternative,
    WeatherRisk,
)
from .optimizer import VEHICLE_PROFILES
from .weather_snapshot import nearest_snapshot_weather


def search_places(query: str) -> list[Place]:
    params = urlencode(
        {
            "q": query,
            "format": "jsonv2",
            "addressdetails": 1,
            "countrycodes": "us",
            "limit": 6,
        }
    )
    request = Request(
        f"https://nominatim.openstreetmap.org/search?{params}",
        headers={"User-Agent": USER_AGENT, "Accept-Language": "en-US,en"},
    )
    try:
        with urlopen(request, timeout=8) as response:
            results = json.load(response)
        return [_place(item) for item in results]
    except Exception:
        return _search_open_meteo_places(query)


def build_directions(command: DirectionsRequest) -> DirectionsPlan:
    candidates = fetch_route_alternatives(
        [
            (command.origin.longitude, command.origin.latitude),
            (command.destination.longitude, command.destination.latitude),
        ]
    )
    alternatives = [
        _score_candidate(index, candidate, command)
        for index, candidate in enumerate(candidates)
    ]
    alternatives.sort(key=lambda item: item.duration_minutes)
    _label_alternatives(alternatives)
    primary = alternatives[0]
    return DirectionsPlan(
        generated_at=datetime.now(UTC),
        origin=command.origin,
        destination=command.destination,
        vehicle_type=command.vehicle_type,
        coordinates=primary.coordinates,
        distance_miles=primary.distance_miles,
        duration_minutes=primary.duration_minutes,
        climate_delay_minutes=primary.climate_delay_minutes,
        risk_score=primary.risk_score,
        weather=primary.weather,
        summary=f"{command.origin.city or 'Origin'} to {command.destination.city or 'destination'}",
        alternatives=alternatives,
    )


def fetch_route_alternatives(
    waypoints: list[tuple[float, float]],
) -> list[tuple[list[list[float]], float, float]]:
    coordinate_string = ";".join(f"{lon},{lat}" for lon, lat in waypoints)
    url = (
        f"https://router.project-osrm.org/route/v1/driving/{coordinate_string}"
        "?overview=full&geometries=geojson&steps=false&alternatives=3"
    )
    with urlopen(url, timeout=12) as response:
        routes = json.load(response).get("routes", [])
    if not routes:
        raise RuntimeError("Routing provider returned no routes")
    return [
        (
            route["geometry"]["coordinates"],
            route["distance"] / 1609.344,
            route["duration"] / 60,
        )
        for route in routes
    ]


def fetch_coordinate_weather(label: str, latitude: float, longitude: float) -> WeatherRisk:
    params = urlencode(
        {
            "latitude": latitude,
            "longitude": longitude,
            "current": "temperature_2m,wind_speed_10m",
            "hourly": "precipitation_probability",
            "temperature_unit": "fahrenheit",
            "wind_speed_unit": "mph",
            "forecast_days": 1,
        }
    )
    try:
        with urlopen(f"https://api.open-meteo.com/v1/forecast?{params}", timeout=7) as response:
            data = json.load(response)
        temperature = float(data["current"]["temperature_2m"])
        wind = float(data["current"]["wind_speed_10m"])
        precipitation = max(data["hourly"]["precipitation_probability"][:6])
        risk = min(100, round(precipitation * 0.6 + wind * 1.4 + max(0, temperature - 92) * 2))
        return WeatherRisk(
            id=f"weather-{latitude:.3f}-{longitude:.3f}",
            city=label,
            latitude=latitude,
            longitude=longitude,
            temperature_f=temperature,
            precipitation_probability=precipitation,
            wind_speed_mph=wind,
            risk_score=risk,
            risk_level="HIGH" if risk >= 60 else "ELEVATED" if risk >= 35 else "LOW",
        )
    except Exception:
        return WeatherRisk(
            id=f"weather-{latitude:.3f}-{longitude:.3f}",
            city=label,
            latitude=latitude,
            longitude=longitude,
            temperature_f=0,
            precipitation_probability=0,
            wind_speed_mph=0,
            risk_score=50,
            risk_level="UNKNOWN",
            data_status="UNAVAILABLE",
        )


def _score_candidate(
    index: int,
    candidate: tuple[list[list[float]], float, float],
    command: DirectionsRequest,
) -> RouteAlternative:
    geometry, distance, base_duration = candidate
    profile = VEHICLE_PROFILES[command.vehicle_type]
    samples = _sample_geometry(geometry, 8)
    weather = fetch_route_weather(samples)
    with ThreadPoolExecutor(max_workers=min(8, len(samples))) as executor:
        alert_results = list(
            executor.map(lambda sample: alerts_for_point_result(sample[2], sample[1]), samples)
        )
    alerts_by_sample = [result[0] for result in alert_results]
    live_alert_samples = sum(result[1] == "LIVE" for result in alert_results)
    live_weather = [item for item in weather if item.data_status == "LIVE"]
    weather_score = round(sum(item.risk_score for item in live_weather) / len(live_weather)) if live_weather else 0
    unique_alerts = {alert.alert_id: alert for alerts in alerts_by_sample for alert in alerts}
    alert_score = max((alert.score for alert in unique_alerts.values()), default=0)
    risk = max(alert_score, weather_score if live_weather else 50)
    data_coverage = (
        round((len(live_weather) + live_alert_samples) / (len(samples) * 2), 2)
        if samples
        else 0
    )
    confidence = "HIGH" if data_coverage >= 0.75 else "MEDIUM" if data_coverage >= 0.4 else "LOW" if unique_alerts else "UNAVAILABLE"
    duration = base_duration * profile["duration"]
    climate_delay = round(duration * (risk / 100) * 0.2 * profile["climate"], 1)
    category_counts = Counter(alert.category for alert in unique_alerts.values())
    hazards = [
        HazardExposure(
            category=category,
            score=max(alert.score for alert in unique_alerts.values() if alert.category == category),
            samples_affected=count,
            summary=f"{count} sampled route area(s) affected",
        )
        for category, count in category_counts.most_common()
    ]
    return RouteAlternative(
        alternative_id=f"route-{index + 1}",
        label="Alternative",
        coordinates=geometry,
        distance_miles=round(distance * profile["distance"], 1),
        duration_minutes=round(duration, 1),
        climate_delay_minutes=climate_delay,
        risk_score=risk,
        weather=weather,
        hazards=hazards,
        data_coverage=data_coverage,
        confidence=confidence,
        source_status={
            "routing": "LIVE",
            "weather": "LIVE" if len(live_weather) == len(samples) else "PARTIAL" if live_weather else "UNAVAILABLE",
            "nws_alerts": "LIVE" if live_alert_samples == len(samples) else "PARTIAL" if live_alert_samples else "UNAVAILABLE",
        },
    )


def _sample_geometry(coordinates: list[list[float]], maximum: int) -> list[tuple[int, float, float]]:
    if len(coordinates) <= maximum:
        indexes = range(len(coordinates))
    else:
        indexes = sorted({round(index * (len(coordinates) - 1) / (maximum - 1)) for index in range(maximum)})
    return [(index, coordinates[index][0], coordinates[index][1]) for index in indexes]


def fetch_route_weather(samples: list[tuple[int, float, float]]) -> list[WeatherRisk]:
    params = urlencode(
        {
            "latitude": ",".join(str(sample[2]) for sample in samples),
            "longitude": ",".join(str(sample[1]) for sample in samples),
            "current": "temperature_2m,wind_speed_10m",
            "hourly": "precipitation_probability",
            "temperature_unit": "fahrenheit",
            "wind_speed_unit": "mph",
            "forecast_days": 1,
        }
    )
    try:
        with urlopen(f"https://api.open-meteo.com/v1/forecast?{params}", timeout=12) as response:
            payload = json.load(response)
        results = payload if isinstance(payload, list) else [payload]
        if len(results) != len(samples):
            raise ValueError("Weather provider returned an unexpected sample count")
        return [
            _weather_from_payload(f"Route sample {position + 1}", sample[2], sample[1], result)
            for position, (sample, result) in enumerate(zip(samples, results, strict=True))
        ]
    except Exception:
        return [
            nearest_snapshot_weather(sample[2], sample[1])
            or WeatherRisk(
                id=f"weather-{sample[2]:.3f}-{sample[1]:.3f}",
                city=f"Route sample {position + 1}",
                latitude=sample[2],
                longitude=sample[1],
                temperature_f=0,
                precipitation_probability=0,
                wind_speed_mph=0,
                risk_score=50,
                risk_level="UNKNOWN",
                data_status="UNAVAILABLE",
            )
            for position, sample in enumerate(samples)
        ]


def _weather_from_payload(label: str, latitude: float, longitude: float, data: dict) -> WeatherRisk:
    temperature = float(data["current"]["temperature_2m"])
    wind = float(data["current"]["wind_speed_10m"])
    precipitation = max(data["hourly"]["precipitation_probability"][:6])
    risk = min(100, round(precipitation * 0.6 + wind * 1.4 + max(0, temperature - 92) * 2))
    return WeatherRisk(
        id=f"weather-{latitude:.3f}-{longitude:.3f}",
        city=label,
        latitude=latitude,
        longitude=longitude,
        temperature_f=temperature,
        precipitation_probability=precipitation,
        wind_speed_mph=wind,
        risk_score=risk,
        risk_level="HIGH" if risk >= 60 else "ELEVATED" if risk >= 35 else "LOW",
    )


def _label_alternatives(alternatives: list[RouteAlternative]) -> None:
    if not alternatives:
        return
    alternatives[0].label = "Fastest"
    lowest_risk = min(alternatives, key=lambda item: (item.risk_score, item.duration_minutes))
    if lowest_risk is not alternatives[0]:
        lowest_risk.label = "Lower weather risk"
    for alternative in alternatives:
        if alternative.label == "Alternative":
            alternative.label = "Balanced"


def _place(item: dict) -> Place:
    address = item.get("address", {})
    city = address.get("city") or address.get("town") or address.get("village") or address.get("county") or ""
    return Place(
        place_id=str(item["place_id"]),
        display_name=item["display_name"],
        city=city,
        state=address.get("state", ""),
        latitude=float(item["lat"]),
        longitude=float(item["lon"]),
    )


def _search_open_meteo_places(query: str) -> list[Place]:
    params = urlencode(
        {
            "name": query,
            "count": 6,
            "countryCode": "US",
            "language": "en",
            "format": "json",
        }
    )
    with urlopen(f"https://geocoding-api.open-meteo.com/v1/search?{params}", timeout=8) as response:
        results = json.load(response).get("results", [])
    return [
        Place(
            place_id=f"open-meteo-{item['id']}",
            display_name=", ".join(
                value
                for value in (item.get("name"), item.get("admin1"), item.get("country"))
                if value
            ),
            city=item.get("name", ""),
            state=item.get("admin1", ""),
            latitude=float(item["latitude"]),
            longitude=float(item["longitude"]),
        )
        for item in results
    ]
