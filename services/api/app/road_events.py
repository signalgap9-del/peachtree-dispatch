import json
import os
from datetime import UTC, datetime
from typing import Any
from urllib.error import URLError
from urllib.parse import urlencode, urlparse
from urllib.request import Request, urlopen

from .models import RoadEventFeed, RoadEventFeedRegistry

WZDX_REGISTRY_URL = os.getenv(
    "WZDX_REGISTRY_URL",
    "https://data.transportation.gov/resource/69qe-yiui.json",
)
WZDX_REGISTRY_SOURCE = "USDOT WZDx Feed Registry"


def get_road_event_feeds(state: str | None = None, limit: int = 30) -> RoadEventFeedRegistry:
    raw_feeds = _fetch_registry(limit=max(limit, 1))
    feeds = [_to_feed(record) for record in raw_feeds if _is_active(record)]
    if state:
        normalized_state = state.strip().lower()
        feeds = [feed for feed in feeds if feed.state.lower() == normalized_state]
    feeds = feeds[:limit]
    return RoadEventFeedRegistry(
        generated_at=datetime.now(UTC),
        source=WZDX_REGISTRY_SOURCE,
        active_feeds=len(feeds),
        no_key_feeds=sum(1 for feed in feeds if not feed.requires_api_key),
        feeds=feeds,
        source_status={"wzdx_registry": "LIVE"},
    )


def _fetch_registry(limit: int) -> list[dict[str, Any]]:
    params = urlencode({"$limit": min(limit, 100), "$order": "state,feedname"})
    request = Request(
        f"{WZDX_REGISTRY_URL}?{params}",
        headers={"User-Agent": "AtmosPath road-events/0.1"},
    )
    try:
        with urlopen(request, timeout=8) as response:
            payload = response.read()
    except URLError as exception:
        raise RuntimeError("WZDx feed registry unavailable") from exception
    decoded = json.loads(payload.decode("utf-8"))
    if not isinstance(decoded, list):
        raise RuntimeError("WZDx feed registry returned an unexpected shape")
    return [item for item in decoded if isinstance(item, dict)]


def _to_feed(record: dict[str, Any]) -> RoadEventFeed:
    endpoint = record.get("url")
    endpoint_url = endpoint.get("url") if isinstance(endpoint, dict) else str(endpoint or "")
    location = record.get("geocoded_column")
    coordinates = location.get("coordinates") if isinstance(location, dict) else None
    longitude = coordinates[0] if isinstance(coordinates, list) and len(coordinates) >= 2 else None
    latitude = coordinates[1] if isinstance(coordinates, list) and len(coordinates) >= 2 else None
    feed_name = _text(record.get("feedname"), "unknown-feed")
    state = _text(record.get("state"), "unknown").lower()

    return RoadEventFeed(
        feed_id=f"{state}:{feed_name}".replace(" ", "-"),
        state=state,
        issuing_organization=_text(record.get("issuingorganization"), "Unknown organization"),
        feed_name=feed_name,
        format=_text(record.get("format"), "unknown"),
        version=_optional_text(record.get("version")),
        update_frequency=_optional_text(record.get("datafeed_frequency_update")),
        active=_is_active(record),
        requires_api_key=_requires_api_key(record, endpoint_url),
        endpoint_host=urlparse(endpoint_url).netloc or None,
        longitude=longitude,
        latitude=latitude,
    )


def _is_active(record: dict[str, Any]) -> bool:
    return str(record.get("active", "")).lower() == "true"


def _requires_api_key(record: dict[str, Any], endpoint_url: str) -> bool:
    if str(record.get("needapikey", "")).lower() == "true":
        return True
    lowered = endpoint_url.lower()
    return "apikey=" in lowered or "api_key=" in lowered


def _text(value: object, fallback: str) -> str:
    text = str(value or "").strip()
    return text or fallback


def _optional_text(value: object) -> str | None:
    text = str(value or "").strip()
    return text or None
