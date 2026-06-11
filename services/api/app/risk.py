from collections import Counter
from datetime import UTC, datetime
from time import monotonic

from .directions import fetch_coordinate_weather
from .hazards import alerts_for_point_result, national_alerts_result
from .models import LocationRisk, NationalRiskOverview, Place, RiskAlert


_NATIONAL_CACHE: tuple[float, NationalRiskOverview] | None = None


def national_risk() -> NationalRiskOverview:
    global _NATIONAL_CACHE
    if _NATIONAL_CACHE and monotonic() - _NATIONAL_CACHE[0] < 60:
        return _NATIONAL_CACHE[1]
    alerts, alert_status = national_alerts_result()
    alerts.sort(key=lambda item: item.score, reverse=True)
    scores = [item.score for item in alerts]
    score = round(sum(sorted(scores, reverse=True)[:20]) / min(20, len(scores))) if scores else 0
    overview = NationalRiskOverview(
        generated_at=datetime.now(UTC),
        score=score,
        level=risk_level(score),
        active_alerts=len(alerts),
        severe_alerts=sum(item.score >= 75 for item in alerts),
        alerts_with_geometry=sum(item.geometry is not None for item in alerts),
        alerts=alerts[:150],
        by_event=dict(Counter(item.event for item in alerts).most_common(10)),
        source_status={"nws_alerts": alert_status},
    )
    _NATIONAL_CACHE = (monotonic(), overview)
    return overview


def location_risk(place: Place) -> LocationRisk:
    alerts, alert_status = alerts_for_point_result(place.latitude, place.longitude)
    weather = fetch_coordinate_weather(
        place.city or place.display_name.split(",")[0], place.latitude, place.longitude
    )
    alert_score = max((item.score for item in alerts), default=0)
    flood_score = max(
        (item.score for item in alerts if "Flood" in item.event), default=0
    )
    wind_score = min(100, round(weather.wind_speed_mph * 3))
    heat_score = min(100, max(0, round((weather.temperature_f - 85) * 6)))
    precipitation_score = weather.precipitation_probability
    category_scores = {
        category.lower(): max((item.score for item in alerts if item.category == category), default=0)
        for category in (
            "TORNADO",
            "TROPICAL_CYCLONE",
            "THUNDERSTORM",
            "WINTER",
            "WILDFIRE",
            "EXTREME_HEAT",
            "WIND",
            "COASTAL",
            "VISIBILITY",
        )
    }
    score = max(
        alert_score,
        round(
            precipitation_score * 0.35
            + wind_score * 0.25
            + heat_score * 0.2
            + flood_score * 0.2
        ),
    )
    return LocationRisk(
        generated_at=datetime.now(UTC),
        place=place,
        score=score,
        level=risk_level(score),
        summary=_summary(score, alerts),
        factors={
            "active_alerts": alert_score,
            "flood": flood_score,
            "precipitation": precipitation_score,
            "wind": wind_score,
            "heat": heat_score,
            **category_scores,
        },
        alerts=alerts,
        weather=weather,
        source_status={
            "nws_alerts": alert_status,
            "weather": weather.data_status,
        },
    )


def risk_level(score: int) -> str:
    if score >= 80:
        return "SEVERE"
    if score >= 55:
        return "HIGH"
    if score >= 30:
        return "MODERATE"
    return "LOW"


def _summary(score: int, alerts: list[RiskAlert]) -> str:
    if alerts:
        return f"{risk_level(score).title()} risk with {len(alerts)} active NWS alert(s)"
    return f"{risk_level(score).title()} near-term driving weather risk; no active NWS alerts"
