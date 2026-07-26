"""Integration tests: provider failure -> breaker opens -> fallback -> health stale."""
from unittest.mock import patch
from urllib.error import URLError

import pytest

from app.resilience import (
    CircuitBreakerOpenError,
    get_breaker,
    ingestion_monitor,
    reset_all_breakers,
    resilient_urlopen,
)
from app.resilience.circuit_breaker import CircuitState


@pytest.fixture(autouse=True)
def clean_state():
    reset_all_breakers()
    yield
    reset_all_breakers()


class TestResilientUrlopen:
    @patch("app.resilience.safe_urlopen")
    def test_success_records_ingestion(self, mock_urlopen):
        mock_urlopen.return_value = _fake_response(b'{"ok": true}')
        result = resilient_urlopen("nws", "https://api.weather.gov/test", timeout=5)
        assert result is not None
        health = ingestion_monitor.health()
        assert health["nws"]["status"] == "ok"
        assert health["nws"]["consecutive_failures"] == 0

    @patch("app.resilience.safe_urlopen")
    def test_failure_records_ingestion_failure(self, mock_urlopen):
        mock_urlopen.side_effect = URLError("connection refused")
        with pytest.raises(URLError):
            resilient_urlopen("nws", "https://api.weather.gov/test", timeout=5)
        health = ingestion_monitor.health()
        assert health["nws"]["consecutive_failures"] == 1

    @patch("app.resilience.safe_urlopen")
    def test_breaker_opens_after_threshold_failures(self, mock_urlopen):
        mock_urlopen.side_effect = URLError("timeout")
        breaker = get_breaker("nws")
        for _ in range(breaker.failure_threshold):
            with pytest.raises(URLError):
                resilient_urlopen("nws", "https://api.weather.gov/test", timeout=5)
        assert breaker.state is CircuitState.OPEN

    @patch("app.resilience.safe_urlopen")
    def test_open_breaker_raises_circuit_error(self, mock_urlopen):
        mock_urlopen.side_effect = URLError("timeout")
        breaker = get_breaker("nws")
        for _ in range(breaker.failure_threshold):
            with pytest.raises(URLError):
                resilient_urlopen("nws", "https://api.weather.gov/test", timeout=5)
        with pytest.raises(CircuitBreakerOpenError):
            resilient_urlopen("nws", "https://api.weather.gov/test", timeout=5)
        # safe_urlopen should NOT have been called again after the breaker opened
        assert mock_urlopen.call_count == breaker.failure_threshold


class TestHazardsFallback:
    """Verify that hazards.py falls back gracefully when NWS is down."""

    @patch("app.resilience.safe_urlopen")
    def test_nws_failure_returns_empty_alerts(self, mock_urlopen):
        mock_urlopen.side_effect = URLError("nws down")
        from app.hazards import alerts_for_point_result

        alerts, status = alerts_for_point_result(33.749, -84.388)
        assert alerts == []
        assert status == "UNAVAILABLE"

    @patch("app.resilience.safe_urlopen")
    def test_nws_breaker_opens_then_fallback(self, mock_urlopen):
        mock_urlopen.side_effect = URLError("nws down")
        from app.hazards import alerts_for_point_result

        breaker = get_breaker("nws")
        # Trip the breaker through the hazards call path
        for _ in range(breaker.failure_threshold + 1):
            alerts, status = alerts_for_point_result(33.749, -84.388)
            assert alerts == []
            assert status == "UNAVAILABLE"

        assert breaker.state is CircuitState.OPEN
        # Subsequent calls still return the fallback
        alerts, status = alerts_for_point_result(33.749, -84.388)
        assert alerts == []
        assert status == "UNAVAILABLE"


class TestHealthEndpointIntegration:
    """Verify the /health endpoint reflects provider staleness."""

    @patch("app.resilience.safe_urlopen")
    def test_health_reports_stale_after_failures(self, mock_urlopen):
        mock_urlopen.side_effect = URLError("down")
        from app.hazards import national_alerts_result

        breaker = get_breaker("nws")
        for _ in range(breaker.failure_threshold):
            national_alerts_result()

        health = ingestion_monitor.health()
        assert health["nws"]["consecutive_failures"] >= breaker.failure_threshold
        # No successful ingestion recorded, so status is "unknown"
        assert health["nws"]["status"] == "unknown"

    def test_health_endpoint_shape(self):
        from fastapi.testclient import TestClient
        from app.main import app

        client = TestClient(app)
        response = client.get("/health")
        assert response.status_code == 200
        body = response.json()
        # Existing fields preserved
        assert body["status"] == "healthy"
        assert body["service"] == "atmospath-risk-engine"
        # New fields present
        assert "providers" in body
        assert "stale_providers" in body
        assert isinstance(body["providers"], dict)
        assert isinstance(body["stale_providers"], list)


class TestWeatherSnapshotFallback:
    """Verify weather_snapshot.py falls back when NWS is unavailable."""

    @patch("app.resilience.safe_urlopen")
    def test_fetch_nws_weather_returns_unavailable(self, mock_urlopen):
        mock_urlopen.side_effect = URLError("nws down")
        from app.weather_snapshot import fetch_nws_weather

        result = fetch_nws_weather("test-point", "Test City", 33.749, -84.388)
        assert result.data_status == "UNAVAILABLE"
        assert result.risk_level == "UNKNOWN"


# -- helpers -----------------------------------------------------------------

class _FakeResponse:
    def __init__(self, data: bytes) -> None:
        self._data = data

    def read(self) -> bytes:
        return self._data

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass


def _fake_response(data: bytes) -> _FakeResponse:
    return _FakeResponse(data)
