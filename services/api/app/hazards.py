import json
from collections.abc import Iterable
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from .models import RiskAlert


USER_AGENT = "AtmosPath/0.2 (https://github.com/signalgap9-del/peachtree-dispatch)"
SEVERITY_SCORES = {
    "Extreme": 100,
    "Severe": 82,
    "Moderate": 58,
    "Minor": 32,
    "Unknown": 20,
}
EVENT_BONUS = {
    "Tornado Warning": 15,
    "Flash Flood Warning": 15,
    "Hurricane Warning": 15,
    "Extreme Heat Warning": 12,
    "Severe Thunderstorm Warning": 10,
    "Flood Warning": 10,
    "Red Flag Warning": 10,
    "Winter Storm Warning": 10,
}


def alerts_for_point(latitude: float, longitude: float) -> list[RiskAlert]:
    return alerts_for_point_result(latitude, longitude)[0]


def alerts_for_point_result(latitude: float, longitude: float) -> tuple[list[RiskAlert], str]:
    query = urlencode({"point": f"{latitude},{longitude}", "status": "actual"})
    features, status = _nws_alerts_result(query)
    return [_alert(feature) for feature in features], status


def national_alerts() -> list[RiskAlert]:
    return national_alerts_result()[0]


def national_alerts_result() -> tuple[list[RiskAlert], str]:
    features, status = _nws_alerts_result("status=actual&message_type=alert")
    return [_alert(feature) for feature in features], status


def alerts_for_route_samples(
    samples: Iterable[tuple[int, float, float]],
) -> tuple[list[list[RiskAlert]], str]:
    alerts, status = national_alerts_result()
    sample_points = list(samples)
    return [
        [
            alert
            for alert in alerts
            if alert.geometry and _geometry_contains(alert.geometry, longitude, latitude)
        ]
        for _, longitude, latitude in sample_points
    ], status


def classify_event(event: str) -> str:
    value = event.lower()
    categories = (
        ("TORNADO", ("tornado",)),
        ("TROPICAL_CYCLONE", ("hurricane", "tropical storm", "tropical cyclone")),
        ("FLOOD", ("flood",)),
        ("THUNDERSTORM", ("thunderstorm", "hail", "lightning")),
        ("WINTER", ("winter", "snow", "ice", "blizzard", "freeze", "frost")),
        ("WILDFIRE", ("red flag", "fire weather", "wildfire")),
        ("EXTREME_HEAT", ("heat",)),
        ("WIND", ("wind",)),
        ("COASTAL", ("coastal", "surf", "rip current")),
        ("VISIBILITY", ("fog", "dust", "smoke", "ash")),
    )
    return next((category for category, words in categories if any(word in value for word in words)), "OTHER")


def _nws_alerts(query: str) -> list[dict]:
    return _nws_alerts_result(query)[0]


def _nws_alerts_result(query: str) -> tuple[list[dict], str]:
    request = Request(
        f"https://api.weather.gov/alerts/active?{query}",
        headers={"User-Agent": USER_AGENT, "Accept": "application/geo+json"},
    )
    try:
        with urlopen(request, timeout=10) as response:
            return json.load(response).get("features", []), "LIVE"
    except Exception:
        return [], "UNAVAILABLE"


def _alert(feature: dict) -> RiskAlert:
    properties = feature.get("properties", {})
    severity = properties.get("severity") or "Unknown"
    event = properties.get("event") or "Weather Alert"
    score = min(100, SEVERITY_SCORES.get(severity, 20) + EVENT_BONUS.get(event, 0))
    longitude, latitude = _centroid(feature.get("geometry"))
    return RiskAlert(
        alert_id=str(properties.get("id") or feature.get("id") or event),
        event=event,
        severity=severity,
        urgency=properties.get("urgency") or "Unknown",
        certainty=properties.get("certainty") or "Unknown",
        headline=properties.get("headline") or event,
        area=properties.get("areaDesc") or "",
        instruction=properties.get("instruction"),
        score=score,
        longitude=longitude,
        latitude=latitude,
        geometry=feature.get("geometry"),
        category=classify_event(event),
    )


def _centroid(geometry: dict | None) -> tuple[float | None, float | None]:
    if not geometry:
        return None, None
    coordinates = geometry.get("coordinates", [])
    while coordinates and isinstance(coordinates[0], list) and coordinates[0] and isinstance(coordinates[0][0], list):
        coordinates = coordinates[0]
    points = [point for point in coordinates if isinstance(point, list) and len(point) >= 2]
    if not points:
        return None, None
    return (
        sum(point[0] for point in points) / len(points),
        sum(point[1] for point in points) / len(points),
    )


def _geometry_contains(geometry: dict, longitude: float, latitude: float) -> bool:
    if geometry.get("type") == "Polygon":
        return _polygon_contains(geometry.get("coordinates", []), longitude, latitude)
    if geometry.get("type") == "MultiPolygon":
        return any(
            _polygon_contains(polygon, longitude, latitude)
            for polygon in geometry.get("coordinates", [])
        )
    return False


def _polygon_contains(rings: list, longitude: float, latitude: float) -> bool:
    if not rings or not _ring_contains(rings[0], longitude, latitude):
        return False
    return not any(_ring_contains(hole, longitude, latitude) for hole in rings[1:])


def _ring_contains(ring: list, longitude: float, latitude: float) -> bool:
    inside = False
    if len(ring) < 3:
        return False
    previous = ring[-1]
    for current in ring:
        x1, y1 = previous[:2]
        x2, y2 = current[:2]
        intersects = (y1 > latitude) != (y2 > latitude)
        if intersects:
            edge_longitude = (x2 - x1) * (latitude - y1) / (y2 - y1) + x1
            if longitude < edge_longitude:
                inside = not inside
        previous = current
    return inside
