"""Tests for app.weather_snapshot cache, S3, and NWS parsing behaviour.

Network and boto3 are mocked; the module-level cache is reset per test.
"""
from __future__ import annotations

import sys
from types import SimpleNamespace
from time import monotonic

import pytest

import app.weather_snapshot as ws
from app.models import NationalWeatherSnapshot, WeatherRisk
from app.weather_snapshot import (
    _distance_miles,
    _load_s3_snapshot,
    _nearest_city_name,
    _wind_speed,
    build_weather_snapshot,
    fetch_nws_weather,
    get_weather_snapshot,
    nearest_snapshot_weather,
    save_weather_snapshot,
)


def _point(point_id: str = "atlanta-i85", label: str = "Atlanta / I-85", status: str = "LIVE", lat: float = 33.749, lon: float = -84.388) -> WeatherRisk:
    return WeatherRisk(
        id=point_id,
        city=label,
        latitude=lat,
        longitude=lon,
        temperature_f=80,
        precipitation_probability=30,
        wind_speed_mph=10,
        risk_score=40,
        risk_level="ELEVATED",
        data_status=status,
        source="NOAA / National Weather Service",
    )


def _snapshot(points=None) -> NationalWeatherSnapshot:
    return NationalWeatherSnapshot(
        generated_at="2026-06-11T00:00:00Z",
        expires_at="2026-06-11T01:15:00Z",
        coverage=1.0,
        points=points if points is not None else [_point()],
    )


@pytest.fixture(autouse=True)
def _reset_cache(monkeypatch):
    monkeypatch.setattr("app.weather_snapshot._CACHE", None)


# ---------------------------------------------------------------------------
# get_weather_snapshot
# ---------------------------------------------------------------------------

def test_get_weather_snapshot_returns_cached(monkeypatch) -> None:
    snapshot = _snapshot()
    monkeypatch.setattr("app.weather_snapshot._CACHE", (monotonic(), snapshot))
    monkeypatch.setattr("app.weather_snapshot.build_weather_snapshot", lambda: pytest.fail("should not build"))

    assert get_weather_snapshot() is snapshot


def test_get_weather_snapshot_loads_from_s3(monkeypatch) -> None:
    snapshot = _snapshot()
    monkeypatch.setattr("app.weather_snapshot._load_s3_snapshot", lambda: snapshot)
    monkeypatch.setattr("app.weather_snapshot.build_weather_snapshot", lambda: pytest.fail("should not build"))

    assert get_weather_snapshot() is snapshot


def test_get_weather_snapshot_builds_when_missing(monkeypatch) -> None:
    snapshot = _snapshot()
    monkeypatch.setattr("app.weather_snapshot._load_s3_snapshot", lambda: None)
    monkeypatch.setattr("app.weather_snapshot.build_weather_snapshot", lambda: snapshot)

    assert get_weather_snapshot() is snapshot


def test_get_weather_snapshot_no_refresh_returns_none(monkeypatch) -> None:
    monkeypatch.setattr("app.weather_snapshot._load_s3_snapshot", lambda: None)
    assert get_weather_snapshot(refresh_if_missing=False) is None


# ---------------------------------------------------------------------------
# build_weather_snapshot / fetch_nws_weather
# ---------------------------------------------------------------------------

def test_build_weather_snapshot_aggregates_points(monkeypatch) -> None:
    monkeypatch.setattr("app.weather_snapshot.fetch_nws_weather", lambda *args: _point())

    snapshot = build_weather_snapshot()

    assert snapshot.coverage == 1.0
    assert len(snapshot.points) == len(ws.INTEREST_POINTS)
    assert snapshot.source_status["nws_forecast"] == "LIVE"


def test_build_weather_snapshot_partial_coverage(monkeypatch) -> None:
    monkeypatch.setattr("app.weather_snapshot.fetch_nws_weather", lambda *args: _point(status="UNAVAILABLE"))

    snapshot = build_weather_snapshot()

    assert snapshot.coverage == 0.0
    assert snapshot.source_status["nws_forecast"] == "UNAVAILABLE"


def test_fetch_nws_weather_failure_is_unavailable(monkeypatch) -> None:
    def boom(url):
        raise RuntimeError("NWS down")

    monkeypatch.setattr("app.weather_snapshot._get_json", boom)

    point = fetch_nws_weather("x", "X", 33.0, -84.0)

    assert point.data_status == "UNAVAILABLE"
    assert point.risk_score == 50
    assert point.risk_level == "UNKNOWN"


# ---------------------------------------------------------------------------
# nearest_snapshot_weather
# ---------------------------------------------------------------------------

def test_nearest_snapshot_weather_no_snapshot(monkeypatch) -> None:
    monkeypatch.setattr("app.weather_snapshot.get_weather_snapshot", lambda refresh_if_missing=False: None)
    assert nearest_snapshot_weather(33.0, -84.0) is None


def test_nearest_snapshot_weather_no_live_points(monkeypatch) -> None:
    monkeypatch.setattr(
        "app.weather_snapshot.get_weather_snapshot",
        lambda refresh_if_missing=False: _snapshot([_point(status="UNAVAILABLE")]),
    )
    assert nearest_snapshot_weather(33.0, -84.0) is None


def test_nearest_snapshot_weather_beyond_radius(monkeypatch) -> None:
    # Seattle point, queried from Miami -> farther than 140 miles.
    monkeypatch.setattr(
        "app.weather_snapshot.get_weather_snapshot",
        lambda refresh_if_missing=False: _snapshot([_point(lat=47.6, lon=-122.3)]),
    )
    assert nearest_snapshot_weather(25.76, -80.19) is None


# ---------------------------------------------------------------------------
# S3 save / load (boto3 mocked)
# ---------------------------------------------------------------------------

def test_save_weather_snapshot_no_bucket_is_noop(monkeypatch) -> None:
    monkeypatch.delenv("WEATHER_SNAPSHOT_BUCKET", raising=False)
    save_weather_snapshot(_snapshot())  # should not raise or require boto3


def test_save_weather_snapshot_uploads_to_s3(monkeypatch) -> None:
    monkeypatch.setenv("WEATHER_SNAPSHOT_BUCKET", "test-bucket")
    calls = []

    class FakeS3:
        def put_object(self, **kwargs):
            calls.append(kwargs)

    monkeypatch.setitem(sys.modules, "boto3", SimpleNamespace(client=lambda name: FakeS3()))

    save_weather_snapshot(_snapshot())

    assert calls[0]["Bucket"] == "test-bucket"
    assert calls[0]["Key"] == "weather/latest.json"


def test_load_s3_snapshot_no_bucket(monkeypatch) -> None:
    monkeypatch.delenv("WEATHER_SNAPSHOT_BUCKET", raising=False)
    assert _load_s3_snapshot() is None


def test_load_s3_snapshot_success(monkeypatch) -> None:
    monkeypatch.setenv("WEATHER_SNAPSHOT_BUCKET", "b")
    body = _snapshot().model_dump_json().encode()

    class FakeBody:
        def read(self):
            return body

    class FakeS3:
        def get_object(self, Bucket, Key):
            return {"Body": FakeBody()}

    monkeypatch.setitem(sys.modules, "boto3", SimpleNamespace(client=lambda name: FakeS3()))

    result = _load_s3_snapshot()
    assert result is not None
    assert result.coverage == 1.0


def test_load_s3_snapshot_error_returns_none(monkeypatch) -> None:
    monkeypatch.setenv("WEATHER_SNAPSHOT_BUCKET", "b")

    class FakeS3:
        def get_object(self, **kwargs):
            raise RuntimeError("access denied")

    monkeypatch.setitem(sys.modules, "boto3", SimpleNamespace(client=lambda name: FakeS3()))

    assert _load_s3_snapshot() is None


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------

def test_wind_speed_parses_ranges_and_defaults() -> None:
    assert _wind_speed("12 to 20 mph") == 20
    assert _wind_speed("5 mph") == 5
    assert _wind_speed("") == 0


def test_distance_miles_zero_for_same_point() -> None:
    assert _distance_miles(33.0, -84.0, 33.0, -84.0) == pytest.approx(0.0)


def test_nearest_city_name_resolves_major_city() -> None:
    assert _nearest_city_name((33.75, -84.39)) == "Atlanta"
