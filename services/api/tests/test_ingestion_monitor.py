"""Tests for the ingestion health monitor."""
import time

import pytest

from app.resilience.ingestion_monitor import IngestionMonitor


@pytest.fixture()
def monitor():
    return IngestionMonitor(stale_threshold=0.3)


class TestRecordSuccess:
    def test_new_provider_reports_ok(self, monitor):
        monitor.record_success("nws")
        health = monitor.health()
        assert health["nws"]["status"] == "ok"
        assert health["nws"]["consecutive_failures"] == 0
        assert health["nws"]["last_success_age_seconds"] is not None

    def test_success_resets_failure_count(self, monitor):
        monitor.record_failure("nws")
        monitor.record_failure("nws")
        monitor.record_success("nws")
        assert monitor.health()["nws"]["consecutive_failures"] == 0


class TestRecordFailure:
    def test_failure_increments_counter(self, monitor):
        monitor.record_failure("open-meteo")
        monitor.record_failure("open-meteo")
        health = monitor.health()
        assert health["open-meteo"]["consecutive_failures"] == 2

    def test_failure_only_provider_reports_unknown(self, monitor):
        monitor.record_failure("osrm")
        health = monitor.health()
        assert health["osrm"]["status"] == "unknown"


class TestStaleness:
    def test_becomes_stale_after_threshold(self, monitor):
        monitor.record_success("nws")
        assert monitor.health()["nws"]["status"] == "ok"
        time.sleep(0.35)
        assert monitor.health()["nws"]["status"] == "stale"

    def test_stale_providers_list(self, monitor):
        monitor.record_success("nws")
        monitor.record_success("open-meteo")
        time.sleep(0.35)
        monitor.record_success("open-meteo")  # refresh one
        stale = monitor.stale_providers()
        assert "nws" in stale
        assert "open-meteo" not in stale

    def test_unknown_provider_in_stale_list(self, monitor):
        monitor.record_failure("osrm")
        assert "osrm" in monitor.stale_providers()


class TestHealthShape:
    def test_empty_monitor_returns_empty_dict(self, monitor):
        assert monitor.health() == {}

    def test_multiple_providers(self, monitor):
        monitor.record_success("nws")
        monitor.record_success("open-meteo")
        monitor.record_failure("osrm")
        health = monitor.health()
        assert set(health.keys()) == {"nws", "open-meteo", "osrm"}

    def test_health_entry_has_required_fields(self, monitor):
        monitor.record_success("nws")
        entry = monitor.health()["nws"]
        assert "provider" in entry
        assert "status" in entry
        assert "last_success_age_seconds" in entry
        assert "consecutive_failures" in entry


class TestReset:
    def test_reset_clears_all_state(self, monitor):
        monitor.record_success("nws")
        monitor.record_failure("osrm")
        monitor.reset()
        assert monitor.health() == {}
