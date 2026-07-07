from __future__ import annotations

import json
import math
import os
from dataclasses import dataclass
from time import monotonic
from typing import Any, Protocol
from urllib.error import URLError
from urllib.request import Request

from ..outbound_http import OutboundRequestError, safe_urlopen
from .edge_risk import EdgeRiskProvider
from .models import EdgeRisk, GeoNode


@dataclass(frozen=True)
class RoadEvent:
    event_id: str
    event_type: str
    severity: str
    latitude: float
    longitude: float
    source: str
    description: str
    risk_score: int


class RoadEventProvider(Protocol):
    source_status: str

    def events_near_edge(self, origin: GeoNode, destination: GeoNode) -> list[RoadEvent]:
        ...


class StaticRoadEventProvider:
    source_status = "STATIC_FIXTURE"

    def __init__(self, events: list[RoadEvent], corridor_radius_miles: float = 15):
        self.events = events
        self.corridor_radius_miles = corridor_radius_miles

    def events_near_edge(self, origin: GeoNode, destination: GeoNode) -> list[RoadEvent]:
        return [
            event
            for event in self.events
            if distance_point_to_segment_miles(
                event.latitude,
                event.longitude,
                origin.latitude,
                origin.longitude,
                destination.latitude,
                destination.longitude,
            )
            <= self.corridor_radius_miles
        ]


class WzdxGeoJsonRoadEventProvider:
    source_status = "WZDX_511_GEOJSON"

    def __init__(
        self,
        feed_urls: list[str],
        timeout_seconds: float = 6,
        cache_ttl_seconds: int = 300,
        max_events_per_feed: int = 500,
        corridor_radius_miles: float = 15,
    ):
        self.feed_urls = [url.strip() for url in feed_urls if url.strip()]
        self.timeout_seconds = timeout_seconds
        self.cache_ttl_seconds = cache_ttl_seconds
        self.max_events_per_feed = max_events_per_feed
        self.corridor_radius_miles = corridor_radius_miles
        self._cache: tuple[float, list[RoadEvent]] | None = None

    @classmethod
    def from_env(cls) -> "WzdxGeoJsonRoadEventProvider | None":
        raw_urls = os.getenv("VRP_ROAD_EVENT_FEED_URLS", "")
        feed_urls = [url for url in raw_urls.split(",") if url.strip()]
        if not feed_urls:
            return None
        return cls(
            feed_urls=feed_urls,
            timeout_seconds=float(os.getenv("VRP_ROAD_EVENT_TIMEOUT_SECONDS", "6")),
            cache_ttl_seconds=int(os.getenv("VRP_ROAD_EVENT_CACHE_TTL_SECONDS", "300")),
            max_events_per_feed=int(os.getenv("VRP_ROAD_EVENT_MAX_EVENTS_PER_FEED", "500")),
            corridor_radius_miles=float(os.getenv("VRP_ROAD_EVENT_CORRIDOR_RADIUS_MILES", "15")),
        )

    def events_near_edge(self, origin: GeoNode, destination: GeoNode) -> list[RoadEvent]:
        events = self._load_events()
        return [
            event
            for event in events
            if distance_point_to_segment_miles(
                event.latitude,
                event.longitude,
                origin.latitude,
                origin.longitude,
                destination.latitude,
                destination.longitude,
            )
            <= self.corridor_radius_miles
        ]

    def _load_events(self) -> list[RoadEvent]:
        now = monotonic()
        if self._cache and now - self._cache[0] <= self.cache_ttl_seconds:
            return self._cache[1]

        events: list[RoadEvent] = []
        for feed_url in self.feed_urls:
            events.extend(self._fetch_feed(feed_url))
        self._cache = (now, events)
        return events

    def _fetch_feed(self, feed_url: str) -> list[RoadEvent]:
        request = Request(feed_url, headers={"User-Agent": "AtmosPath road-event-risk/0.2"})
        try:
            with safe_urlopen(request, timeout=self.timeout_seconds) as response:
                payload = response.read()
        except (OutboundRequestError, URLError, TimeoutError, OSError):
            return []

        try:
            decoded = json.loads(payload.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return []
        return road_events_from_geojson(decoded, source=feed_url, limit=self.max_events_per_feed)


class RoadEventEdgeRiskProvider:
    def __init__(self, base_provider: EdgeRiskProvider, road_event_provider: RoadEventProvider):
        self.base_provider = base_provider
        self.road_event_provider = road_event_provider
        self.source_status = f"{getattr(base_provider, 'source_status', 'RULE_BASED')}+{road_event_provider.source_status}"

    def score_edge(self, origin: GeoNode, destination: GeoNode) -> EdgeRisk:
        base = self.base_provider.score_edge(origin, destination)
        events = self.road_event_provider.events_near_edge(origin, destination)
        if not events:
            return base

        strongest = max(events, key=lambda event: event.risk_score)
        traffic_risk = max(base.traffic_risk_score, strongest.risk_score)
        event_summaries = [
            f"{event.event_type}:{event.severity}:{event.risk_score}" for event in sorted(events, key=lambda item: item.risk_score, reverse=True)[:3]
        ]
        return base.model_copy(
            update={
                "traffic_risk_score": traffic_risk,
                "primary_hazard": strongest.event_type or base.primary_hazard or "Road event",
                "source_coverage": max(base.source_coverage, 0.75),
                "explanation": [
                    *base.explanation,
                    f"road_event_join={len(events)} corridor events",
                    f"road_event_provider={self.road_event_provider.source_status}",
                    *event_summaries,
                ],
            }
        )


def build_default_road_event_edge_risk_provider(base_provider: EdgeRiskProvider) -> EdgeRiskProvider:
    provider = WzdxGeoJsonRoadEventProvider.from_env()
    if provider is None:
        return base_provider
    return RoadEventEdgeRiskProvider(base_provider, provider)


def road_events_from_geojson(decoded: Any, source: str, limit: int = 500) -> list[RoadEvent]:
    features = decoded.get("features") if isinstance(decoded, dict) else None
    if not isinstance(features, list):
        return []

    events: list[RoadEvent] = []
    for index, feature in enumerate(features[:limit]):
        if not isinstance(feature, dict):
            continue
        coordinates = _representative_coordinate(feature.get("geometry"))
        if coordinates is None:
            continue
        longitude, latitude = coordinates
        properties = feature.get("properties") if isinstance(feature.get("properties"), dict) else {}
        event_type = _event_type(properties)
        severity = _severity(properties)
        events.append(
            RoadEvent(
                event_id=str(properties.get("id") or properties.get("event_id") or feature.get("id") or f"{source}#{index}"),
                event_type=event_type,
                severity=severity,
                latitude=latitude,
                longitude=longitude,
                source=_safe_source(source),
                description=_description(properties, event_type),
                risk_score=_risk_score(event_type, severity, properties),
            )
        )
    return events


def distance_point_to_segment_miles(
    point_latitude: float,
    point_longitude: float,
    start_latitude: float,
    start_longitude: float,
    end_latitude: float,
    end_longitude: float,
) -> float:
    reference_latitude = math.radians((start_latitude + end_latitude + point_latitude) / 3)
    x = lambda lon: math.radians(lon) * math.cos(reference_latitude) * 3958.8
    y = lambda lat: math.radians(lat) * 3958.8

    px, py = x(point_longitude), y(point_latitude)
    ax, ay = x(start_longitude), y(start_latitude)
    bx, by = x(end_longitude), y(end_latitude)
    dx, dy = bx - ax, by - ay
    if dx == 0 and dy == 0:
        return math.hypot(px - ax, py - ay)
    projection = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    closest_x = ax + projection * dx
    closest_y = ay + projection * dy
    return math.hypot(px - closest_x, py - closest_y)


def _representative_coordinate(geometry: Any) -> tuple[float, float] | None:
    if not isinstance(geometry, dict):
        return None
    coordinates = geometry.get("coordinates")
    geometry_type = str(geometry.get("type") or "").lower()
    if geometry_type == "point" and isinstance(coordinates, list) and len(coordinates) >= 2:
        return _coordinate_pair(coordinates)
    if geometry_type in {"linestring", "multilinestring"}:
        flattened = _flatten_coordinates(coordinates)
        if flattened:
            longitude = sum(pair[0] for pair in flattened) / len(flattened)
            latitude = sum(pair[1] for pair in flattened) / len(flattened)
            return longitude, latitude
    return None


def _flatten_coordinates(coordinates: Any) -> list[tuple[float, float]]:
    if not isinstance(coordinates, list):
        return []
    if coordinates and isinstance(coordinates[0], (int, float)):
        pair = _coordinate_pair(coordinates)
        return [pair] if pair else []
    pairs: list[tuple[float, float]] = []
    for item in coordinates:
        pairs.extend(_flatten_coordinates(item))
    return pairs


def _coordinate_pair(value: list[Any]) -> tuple[float, float] | None:
    try:
        longitude = float(value[0])
        latitude = float(value[1])
    except (TypeError, ValueError):
        return None
    if not (-180 <= longitude <= 180 and -90 <= latitude <= 90):
        return None
    return longitude, latitude


def _event_type(properties: dict[str, Any]) -> str:
    candidates = [
        properties.get("event_type"),
        properties.get("eventType"),
        properties.get("road_event_type"),
        properties.get("type"),
        properties.get("activity_type"),
        properties.get("core_details", {}).get("event_type") if isinstance(properties.get("core_details"), dict) else None,
    ]
    return _humanize(next((candidate for candidate in candidates if candidate), "Road event"))


def _severity(properties: dict[str, Any]) -> str:
    candidates = [
        properties.get("severity"),
        properties.get("impact"),
        properties.get("vehicle_impact"),
        properties.get("lane_status"),
        properties.get("lanes_closed"),
    ]
    return _humanize(next((candidate for candidate in candidates if candidate), "unknown"))


def _description(properties: dict[str, Any], event_type: str) -> str:
    value = properties.get("description") or properties.get("headline") or properties.get("name") or event_type
    return str(value)[:240]


def _risk_score(event_type: str, severity: str, properties: dict[str, Any]) -> int:
    text = f"{event_type} {severity} {properties}".lower()
    if any(token in text for token in ("closed", "closure", "detour", "blocked")):
        return 85
    if any(token in text for token in ("crash", "incident", "accident")):
        return 75
    if any(token in text for token in ("lane", "restriction", "reduced")):
        return 60
    if any(token in text for token in ("work", "construction", "maintenance")):
        return 55
    return 35


def _humanize(value: Any) -> str:
    text = str(value or "").replace("_", " ").replace("-", " ").strip()
    return text.title() if text else "Road Event"


def _safe_source(source: str) -> str:
    return source.split("?", 1)[0][:200]
