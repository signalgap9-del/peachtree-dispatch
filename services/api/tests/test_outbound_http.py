from urllib.request import Request

import pytest

from app.outbound_http import OutboundRequestError, assert_safe_outbound_url, safe_urlopen


def test_blocks_cloud_metadata_endpoint_by_default(monkeypatch) -> None:
    monkeypatch.delenv("ATMOSPATH_ALLOW_LOCAL_OUTBOUND", raising=False)

    with pytest.raises(OutboundRequestError, match="local or private"):
        assert_safe_outbound_url("http://169.254.169.254/latest/meta-data")


def test_blocks_unknown_provider_even_over_https(monkeypatch) -> None:
    monkeypatch.setenv("ATMOSPATH_VALIDATE_OUTBOUND_DNS", "false")

    with pytest.raises(OutboundRequestError, match="not allowlisted"):
        assert_safe_outbound_url("https://example.com/weather")


def test_allows_default_weather_provider_when_dns_guard_is_disabled(monkeypatch) -> None:
    monkeypatch.setenv("ATMOSPATH_VALIDATE_OUTBOUND_DNS", "false")

    assert_safe_outbound_url("https://api.weather.gov/alerts/active?status=actual")


def test_allows_custom_provider_only_when_explicitly_allowlisted(monkeypatch) -> None:
    monkeypatch.setenv("ATMOSPATH_VALIDATE_OUTBOUND_DNS", "false")
    monkeypatch.setenv("ATMOSPATH_ALLOWED_OUTBOUND_HOSTS", "feeds.example.test")

    assert_safe_outbound_url("https://feeds.example.test/wzdx")


def test_local_osrm_requires_explicit_local_development_flag(monkeypatch) -> None:
    monkeypatch.delenv("ATMOSPATH_ALLOW_LOCAL_OUTBOUND", raising=False)

    with pytest.raises(OutboundRequestError):
        assert_safe_outbound_url("http://localhost:5000/table/v1/driving/0,0;1,1")

    monkeypatch.setenv("ATMOSPATH_ALLOW_LOCAL_OUTBOUND", "true")
    assert_safe_outbound_url("http://localhost:5000/table/v1/driving/0,0;1,1")


def test_rejects_embedded_credentials(monkeypatch) -> None:
    monkeypatch.setenv("ATMOSPATH_VALIDATE_OUTBOUND_DNS", "false")

    with pytest.raises(OutboundRequestError, match="credentials"):
        assert_safe_outbound_url("https://token@example.com/weather")


def test_safe_urlopen_validates_request_before_network(monkeypatch) -> None:
    called = False

    def fake_open(*args, **kwargs):
        nonlocal called
        called = True
        raise AssertionError("network should not be attempted")

    monkeypatch.setattr("app.outbound_http._NO_REDIRECT_OPENER.open", fake_open)

    with pytest.raises(OutboundRequestError, match="not allowlisted"):
        safe_urlopen(Request("https://example.com/weather"), timeout=1)

    assert called is False
