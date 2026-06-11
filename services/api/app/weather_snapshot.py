import json
import os
import re
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime, timedelta
from math import asin, cos, radians, sin, sqrt
from time import monotonic
from urllib.request import Request, urlopen

from .hazards import USER_AGENT
from .models import NationalWeatherSnapshot, WeatherRisk


MAJOR_CITY_POINTS = [
    ("seattle-i5", "Seattle / I-5", 47.6062, -122.3321),
    ("portland-i5", "Portland / I-5", 45.5152, -122.6784),
    ("san-francisco-i80", "San Francisco / I-80", 37.7749, -122.4194),
    ("los-angeles-i10", "Los Angeles / I-10", 34.0522, -118.2437),
    ("san-diego-i5", "San Diego / I-5", 32.7157, -117.1611),
    ("phoenix-i10", "Phoenix / I-10", 33.4484, -112.0740),
    ("salt-lake-i80", "Salt Lake City / I-80", 40.7608, -111.8910),
    ("denver-i70", "Denver / I-70", 39.7392, -104.9903),
    ("el-paso-i10", "El Paso / I-10", 31.7619, -106.4850),
    ("dallas-i35", "Dallas / I-35", 32.7767, -96.7970),
    ("houston-i10", "Houston / I-10", 29.7604, -95.3698),
    ("san-antonio-i35", "San Antonio / I-35", 29.4241, -98.4936),
    ("kansas-city-i70", "Kansas City / I-70", 39.0997, -94.5786),
    ("minneapolis-i94", "Minneapolis / I-94", 44.9778, -93.2650),
    ("chicago-i90", "Chicago / I-90", 41.8781, -87.6298),
    ("st-louis-i70", "St. Louis / I-70", 38.6270, -90.1994),
    ("nashville-i40", "Nashville / I-40", 36.1627, -86.7816),
    ("atlanta-i85", "Atlanta / I-85", 33.7490, -84.3880),
    ("miami-i95", "Miami / I-95", 25.7617, -80.1918),
    ("jacksonville-i95", "Jacksonville / I-95", 30.3322, -81.6557),
    ("charlotte-i85", "Charlotte / I-85", 35.2271, -80.8431),
    ("washington-i95", "Washington / I-95", 38.9072, -77.0369),
    ("philadelphia-i95", "Philadelphia / I-95", 39.9526, -75.1652),
    ("new-york-i95", "New York / I-95", 40.7128, -74.0060),
    ("boston-i90", "Boston / I-90", 42.3601, -71.0589),
    ("las-vegas-i15", "Las Vegas / I-15", 36.1699, -115.1398),
    ("albuquerque-i40", "Albuquerque / I-40", 35.0844, -106.6504),
    ("oklahoma-city-i40", "Oklahoma City / I-40", 35.4676, -97.5164),
    ("omaha-i80", "Omaha / I-80", 41.2565, -95.9345),
    ("des-moines-i80", "Des Moines / I-80", 41.5868, -93.6250),
    ("milwaukee-i94", "Milwaukee / I-94", 43.0389, -87.9065),
    ("detroit-i75", "Detroit / I-75", 42.3314, -83.0458),
    ("cleveland-i90", "Cleveland / I-90", 41.4993, -81.6944),
    ("pittsburgh-i76", "Pittsburgh / I-76", 40.4406, -79.9959),
    ("indianapolis-i70", "Indianapolis / I-70", 39.7684, -86.1581),
    ("louisville-i65", "Louisville / I-65", 38.2527, -85.7585),
    ("memphis-i40", "Memphis / I-40", 35.1495, -90.0490),
    ("new-orleans-i10", "New Orleans / I-10", 29.9511, -90.0715),
    ("birmingham-i20", "Birmingham / I-20", 33.5186, -86.8104),
    ("raleigh-i40", "Raleigh / I-40", 35.7796, -78.6382),
    ("richmond-i95", "Richmond / I-95", 37.5407, -77.4360),
    ("norfolk-i64", "Norfolk / I-64", 36.8508, -76.2859),
    ("orlando-i4", "Orlando / I-4", 28.5383, -81.3792),
    ("tampa-i75", "Tampa / I-75", 27.9506, -82.4572),
    ("sacramento-i80", "Sacramento / I-80", 38.5816, -121.4944),
    ("boise-i84", "Boise / I-84", 43.6150, -116.2023),
    ("spokane-i90", "Spokane / I-90", 47.6588, -117.4260),
]

CORRIDORS = {
    "I-5": [(32.72, -117.16), (34.05, -118.24), (38.58, -121.49), (45.52, -122.68), (47.61, -122.33)],
    "I-10": [(34.05, -118.24), (33.45, -112.07), (31.76, -106.49), (29.42, -98.49), (29.76, -95.37), (29.95, -90.07), (30.42, -87.22)],
    "I-20": [(32.45, -99.73), (32.78, -96.80), (32.30, -90.18), (33.52, -86.81), (33.75, -84.39), (34.00, -81.03)],
    "I-35": [(29.42, -98.49), (30.27, -97.74), (32.78, -96.80), (35.47, -97.52), (39.10, -94.58), (44.98, -93.27)],
    "I-40": [(34.15, -118.14), (35.08, -106.65), (35.47, -97.52), (35.47, -97.52), (35.15, -90.05), (36.16, -86.78), (35.78, -78.64)],
    "I-70": [(39.74, -104.99), (39.10, -94.58), (38.63, -90.20), (39.77, -86.16), (39.96, -82.99), (39.29, -76.61)],
    "I-75": [(25.76, -80.19), (27.95, -82.46), (33.75, -84.39), (39.10, -84.51), (42.33, -83.05)],
    "I-80": [(37.77, -122.42), (40.76, -111.89), (41.26, -95.93), (41.88, -87.63), (41.50, -81.69), (40.71, -74.01)],
    "I-90": [(47.61, -122.33), (47.66, -117.43), (44.98, -93.27), (41.88, -87.63), (42.36, -71.06)],
    "I-95": [(25.76, -80.19), (30.33, -81.66), (35.23, -80.84), (38.91, -77.04), (39.95, -75.17), (40.71, -74.01), (42.36, -71.06)],
}

INTEREST_POINTS = []
INTEREST_POINTS.extend(MAJOR_CITY_POINTS)
for corridor, vertices in CORRIDORS.items():
    for segment_index, (start, end) in enumerate(zip(vertices, vertices[1:]), start=1):
        for sample_index in range(1, 4):
            fraction = sample_index / 4
            latitude = start[0] + (end[0] - start[0]) * fraction
            longitude = start[1] + (end[1] - start[1]) * fraction
            INTEREST_POINTS.append(
                (
                    f"{corridor.lower()}-{segment_index}-{sample_index}",
                    f"{corridor} corridor sample",
                    round(latitude, 4),
                    round(longitude, 4),
                )
            )

_CACHE: tuple[float, NationalWeatherSnapshot] | None = None


def get_weather_snapshot(refresh_if_missing: bool = True) -> NationalWeatherSnapshot | None:
    global _CACHE
    if _CACHE and monotonic() - _CACHE[0] < 3300:
        return _CACHE[1]
    snapshot = _load_s3_snapshot()
    if snapshot:
        _CACHE = (monotonic(), snapshot)
        return snapshot
    if not refresh_if_missing:
        return None
    snapshot = build_weather_snapshot()
    _CACHE = (monotonic(), snapshot)
    return snapshot


def build_weather_snapshot() -> NationalWeatherSnapshot:
    with ThreadPoolExecutor(max_workers=8) as executor:
        points = list(executor.map(lambda point: fetch_nws_weather(*point), INTEREST_POINTS))
    live = [point for point in points if point.data_status == "LIVE"]
    now = datetime.now(UTC)
    return NationalWeatherSnapshot(
        generated_at=now,
        expires_at=now + timedelta(minutes=75),
        coverage=round(len(live) / len(points), 2),
        points=points,
        source_status={"nws_forecast": "LIVE" if len(live) == len(points) else "PARTIAL" if live else "UNAVAILABLE"},
    )


def fetch_nws_weather(identifier: str, label: str, latitude: float, longitude: float) -> WeatherRisk:
    try:
        point = _get_json(f"https://api.weather.gov/points/{latitude:.4f},{longitude:.4f}")
        forecast_url = point["properties"]["forecastHourly"]
        periods = _get_json(forecast_url)["properties"]["periods"][:6]
        temperatures = [float(period["temperature"]) for period in periods]
        winds = [_wind_speed(period.get("windSpeed", "0 mph")) for period in periods]
        precipitation = [
            float((period.get("probabilityOfPrecipitation") or {}).get("value") or 0)
            for period in periods
        ]
        temperature = temperatures[0]
        wind = max(winds)
        pop = max(precipitation)
        risk = weather_risk_score(temperature, pop, wind, [period.get("shortForecast", "") for period in periods])
        return WeatherRisk(
            id=identifier,
            city=label,
            latitude=latitude,
            longitude=longitude,
            temperature_f=temperature,
            precipitation_probability=pop,
            wind_speed_mph=wind,
            risk_score=risk,
            risk_level="HIGH" if risk >= 60 else "ELEVATED" if risk >= 35 else "LOW",
            source="NOAA / National Weather Service",
        )
    except Exception:
        return WeatherRisk(
            id=identifier,
            city=label,
            latitude=latitude,
            longitude=longitude,
            temperature_f=0,
            precipitation_probability=0,
            wind_speed_mph=0,
            risk_score=50,
            risk_level="UNKNOWN",
            data_status="UNAVAILABLE",
            source="NOAA / National Weather Service",
        )


def weather_risk_score(temperature: float, precipitation: float, wind: float, forecasts: list[str]) -> int:
    text = " ".join(forecasts).lower()
    severe_bonus = 25 if any(word in text for word in ("thunderstorm", "tornado", "hurricane", "blizzard", "ice")) else 0
    heat = max(0, temperature - 95) * 2
    cold = max(0, 20 - temperature) * 1.5
    return min(100, round(precipitation * 0.5 + wind * 1.1 + heat + cold + severe_bonus))


def nearest_snapshot_weather(latitude: float, longitude: float, maximum_miles: float = 140) -> WeatherRisk | None:
    snapshot = get_weather_snapshot(refresh_if_missing=False)
    if not snapshot:
        return None
    candidates = [
        (_distance_miles(latitude, longitude, point.latitude, point.longitude), point)
        for point in snapshot.points
        if point.data_status == "LIVE"
    ]
    if not candidates:
        return None
    distance, point = min(candidates, key=lambda candidate: candidate[0])
    if distance > maximum_miles:
        return None
    return point.model_copy(
        update={
            "id": f"snapshot-{latitude:.3f}-{longitude:.3f}",
            "city": f"NOAA snapshot near {point.city}",
            "latitude": latitude,
            "longitude": longitude,
        }
    )


def save_weather_snapshot(snapshot: NationalWeatherSnapshot) -> None:
    bucket = os.getenv("WEATHER_SNAPSHOT_BUCKET")
    if not bucket:
        return
    import boto3

    boto3.client("s3").put_object(
        Bucket=bucket,
        Key="weather/latest.json",
        Body=snapshot.model_dump_json().encode(),
        ContentType="application/json",
        CacheControl="max-age=300",
    )


def _load_s3_snapshot() -> NationalWeatherSnapshot | None:
    bucket = os.getenv("WEATHER_SNAPSHOT_BUCKET")
    if not bucket:
        return None
    try:
        import boto3

        body = boto3.client("s3").get_object(Bucket=bucket, Key="weather/latest.json")["Body"].read()
        return NationalWeatherSnapshot.model_validate_json(body)
    except Exception:
        return None


def _get_json(url: str) -> dict:
    request = Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/geo+json"})
    with urlopen(request, timeout=10) as response:
        return json.load(response)


def _wind_speed(value: str) -> float:
    values = [float(item) for item in re.findall(r"\d+(?:\.\d+)?", value)]
    return max(values, default=0)


def _distance_miles(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    radius = 3958.8
    dlat = radians(lat2 - lat1)
    dlon = radians(lon2 - lon1)
    value = sin(dlat / 2) ** 2 + cos(radians(lat1)) * cos(radians(lat2)) * sin(dlon / 2) ** 2
    return 2 * radius * asin(sqrt(value))
