"""Tests for app.weather_raster rendering, manifest, and S3/local storage."""
from __future__ import annotations

import json
import sys
from types import SimpleNamespace

import numpy as np
import pytest

from app.models import NationalWeatherSnapshot, WeatherRasterManifest, WeatherRisk
from app.weather_raster import (
    _colorize,
    _transparent_png,
    get_weather_raster_manifest,
    get_weather_raster_png,
    render_weather_raster,
    save_weather_raster,
)


def _point(status: str = "LIVE") -> WeatherRisk:
    return WeatherRisk(
        id="atlanta-i85",
        city="Atlanta / I-85",
        latitude=33.749,
        longitude=-84.388,
        temperature_f=80,
        precipitation_probability=30,
        wind_speed_mph=10,
        risk_score=60,
        risk_level="HIGH",
        data_status=status,
    )


def _snapshot(points=None) -> NationalWeatherSnapshot:
    return NationalWeatherSnapshot(
        generated_at="2026-06-11T00:00:00Z",
        expires_at="2026-06-11T01:15:00Z",
        coverage=1.0,
        points=points if points is not None else [_point()],
    )


@pytest.fixture(autouse=True)
def _reset_locals(monkeypatch):
    monkeypatch.setattr("app.weather_raster._LOCAL_PNG", None)
    monkeypatch.setattr("app.weather_raster._LOCAL_MANIFEST", None)


def test_render_weather_raster_no_live_points_is_transparent() -> None:
    png = render_weather_raster(_snapshot([_point(status="UNAVAILABLE")]))
    assert png.startswith(b"\x89PNG")


def test_save_weather_raster_local_without_bucket(monkeypatch) -> None:
    monkeypatch.delenv("WEATHER_SNAPSHOT_BUCKET", raising=False)
    snapshot = _snapshot()

    manifest = save_weather_raster(snapshot, b"png-bytes")

    assert manifest.url == "http://127.0.0.1:8000/risk/weather-raster.png"
    assert manifest.point_count == 1
    import app.weather_raster as wr
    assert wr._LOCAL_PNG == b"png-bytes"
    assert wr._LOCAL_MANIFEST is manifest


def test_save_weather_raster_uploads_to_s3(monkeypatch) -> None:
    monkeypatch.setenv("WEATHER_SNAPSHOT_BUCKET", "b")
    calls = []

    class FakeS3:
        def put_object(self, **kwargs):
            calls.append(kwargs)

        def generate_presigned_url(self, op, Params, ExpiresIn):
            return "https://s3/presigned.png"

    monkeypatch.setitem(sys.modules, "boto3", SimpleNamespace(client=lambda name: FakeS3()))

    manifest = save_weather_raster(_snapshot(), b"png")

    assert manifest.url == "https://s3/presigned.png"
    keys = {call["Key"] for call in calls}
    assert keys == {"weather/latest.png", "weather/manifest.json"}


def test_get_weather_raster_manifest_prefers_local(monkeypatch) -> None:
    manifest = WeatherRasterManifest(
        generated_at="2026-06-11T00:00:00Z",
        expires_at="2026-06-11T01:15:00Z",
        layer="composite-driving-risk",
        source="test",
        url="http://local/manifest",
        bounds=[[-125.0, 24.0], [-66.0, 50.0]],
        point_count=1,
        coverage=1.0,
    )
    monkeypatch.setattr("app.weather_raster._LOCAL_MANIFEST", manifest)

    assert get_weather_raster_manifest() is manifest


def test_get_weather_raster_manifest_from_s3(monkeypatch) -> None:
    monkeypatch.setenv("WEATHER_SNAPSHOT_BUCKET", "b")
    payload = {
        "generated_at": "2026-06-11T00:00:00Z",
        "expires_at": "2026-06-11T01:15:00Z",
        "layer": "composite-driving-risk",
        "source": "test",
        "url": "http://stale/url",
        "bounds": [[-125.0, 24.0], [-66.0, 50.0]],
        "point_count": 3,
        "coverage": 0.9,
    }

    class FakeBody:
        def read(self):
            return json.dumps(payload).encode()

    class FakeS3:
        def get_object(self, Bucket, Key):
            return {"Body": FakeBody()}

        def generate_presigned_url(self, op, Params, ExpiresIn):
            return "https://s3/fresh.png"

    monkeypatch.setitem(sys.modules, "boto3", SimpleNamespace(client=lambda name: FakeS3()))

    manifest = get_weather_raster_manifest()

    assert manifest.url == "https://s3/fresh.png"
    assert manifest.point_count == 3


def test_get_weather_raster_manifest_falls_back_to_snapshot(monkeypatch) -> None:
    monkeypatch.delenv("WEATHER_SNAPSHOT_BUCKET", raising=False)
    snapshot = _snapshot()
    monkeypatch.setattr("app.weather_raster.get_weather_snapshot", lambda: snapshot)

    manifest = get_weather_raster_manifest()

    assert manifest.point_count == 1


def test_get_weather_raster_png_prefers_local(monkeypatch) -> None:
    monkeypatch.setattr("app.weather_raster._LOCAL_PNG", b"cached-png")
    assert get_weather_raster_png() == b"cached-png"


def test_get_weather_raster_png_builds_from_snapshot(monkeypatch) -> None:
    monkeypatch.delenv("WEATHER_SNAPSHOT_BUCKET", raising=False)
    monkeypatch.setattr("app.weather_raster.get_weather_snapshot", lambda: _snapshot())

    png = get_weather_raster_png()

    assert png.startswith(b"\x89PNG")


def test_colorize_produces_rgba_uint8() -> None:
    scores = np.array([[0, 50], [100, 25]], dtype=np.float32)
    rgba = _colorize(scores)
    assert rgba.shape == (2, 2, 4)
    assert rgba.dtype == np.uint8


def test_transparent_png_is_valid() -> None:
    assert _transparent_png().startswith(b"\x89PNG")
