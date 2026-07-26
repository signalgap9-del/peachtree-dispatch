from __future__ import annotations

import ipaddress
import os
import socket
from functools import lru_cache
from urllib.error import HTTPError
from urllib.parse import urlparse
from urllib.request import HTTPRedirectHandler, OpenerDirector, Request, build_opener


DEFAULT_ALLOWED_OUTBOUND_HOSTS = frozenset(
    {
        "api.open-meteo.com",
        "api.weather.gov",
        "data.transportation.gov",
        "geocoding-api.open-meteo.com",
        "nominatim.openstreetmap.org",
        "router.project-osrm.org",
        # Google Routes API (production routing provider, US-only).
        "routes.googleapis.com",
        # MapTiler base-map tiles/styles. Tiles are normally fetched by the
        # browser; this entry covers server-side map rendering if added later.
        "api.maptiler.com",
    }
)

LOCAL_DEVELOPMENT_HOSTS = frozenset({"localhost", "127.0.0.1", "::1"})


class OutboundRequestError(RuntimeError):
    """Raised when an outbound provider URL violates the application allowlist."""


class _NoRedirectHandler(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # type: ignore[no-untyped-def]
        raise OutboundRequestError(f"outbound redirect blocked: HTTP {code} to {newurl}")


_NO_REDIRECT_OPENER: OpenerDirector = build_opener(_NoRedirectHandler)


def safe_urlopen(url_or_request: str | Request, *, timeout: float):
    """Open a URL only after applying AtmosPath outbound provider policy.

    This wraps urllib for the app's external data providers. It intentionally
    blocks arbitrary hosts, redirects, embedded credentials, and local/private
    network targets unless local development egress is explicitly enabled.
    """

    url = url_or_request.full_url if isinstance(url_or_request, Request) else url_or_request
    assert_safe_outbound_url(url)
    try:
        return _NO_REDIRECT_OPENER.open(url_or_request, timeout=timeout)
    except HTTPError:
        raise


def assert_safe_outbound_url(url: str) -> None:
    parsed = urlparse(url)
    hostname = _normalized_hostname(parsed.hostname)
    if not hostname:
        raise OutboundRequestError("outbound URL must include a hostname")
    if parsed.username or parsed.password:
        raise OutboundRequestError("outbound URL must not include embedded credentials")

    allow_local = _allow_local_outbound()
    if _is_ip_or_local_network(hostname):
        if not (allow_local and _is_local_hostname(hostname)):
            raise OutboundRequestError("outbound URL must not target local or private networks")
        if parsed.scheme not in {"http", "https"}:
            raise OutboundRequestError("outbound URL must use HTTP or HTTPS")
        return

    if parsed.scheme != "https":
        raise OutboundRequestError("outbound URL must use HTTPS")

    allowed_hosts = DEFAULT_ALLOWED_OUTBOUND_HOSTS | _configured_allowed_hosts()
    if hostname not in allowed_hosts:
        raise OutboundRequestError(f"outbound host is not allowlisted: {hostname}")

    if _dns_guard_enabled():
        _assert_resolves_to_public_addresses(hostname)


def _normalized_hostname(hostname: str | None) -> str | None:
    if hostname is None:
        return None
    return hostname.strip().rstrip(".").lower() or None


def _configured_allowed_hosts() -> frozenset[str]:
    raw_hosts = os.getenv("ATMOSPATH_ALLOWED_OUTBOUND_HOSTS", "")
    return frozenset(
        host.strip().rstrip(".").lower()
        for host in raw_hosts.split(",")
        if host.strip()
    )


def _allow_local_outbound() -> bool:
    return os.getenv("ATMOSPATH_ALLOW_LOCAL_OUTBOUND", "").lower() in {"1", "true", "yes"}


def _dns_guard_enabled() -> bool:
    return os.getenv("ATMOSPATH_VALIDATE_OUTBOUND_DNS", "true").lower() not in {"0", "false", "no"}


def _is_local_hostname(hostname: str) -> bool:
    return hostname in LOCAL_DEVELOPMENT_HOSTS


def _is_ip_or_local_network(hostname: str) -> bool:
    if _is_local_hostname(hostname):
        return True
    try:
        return _is_blocked_address(ipaddress.ip_address(hostname))
    except ValueError:
        return False


def _assert_resolves_to_public_addresses(hostname: str) -> None:
    addresses = _resolved_addresses(hostname)
    if not addresses:
        raise OutboundRequestError(f"outbound host did not resolve: {hostname}")
    blocked = [address for address in addresses if _is_blocked_address(address)]
    if blocked:
        raise OutboundRequestError(f"outbound host resolved to blocked address: {hostname}")


@lru_cache(maxsize=256)
def _resolved_addresses(hostname: str) -> tuple[ipaddress.IPv4Address | ipaddress.IPv6Address, ...]:
    try:
        results = socket.getaddrinfo(hostname, None, proto=socket.IPPROTO_TCP)
    except OSError as exc:
        raise OutboundRequestError(f"outbound host DNS lookup failed: {hostname}") from exc
    addresses = []
    for result in results:
        address = result[4][0]
        try:
            addresses.append(ipaddress.ip_address(address))
        except ValueError:
            continue
    return tuple(addresses)


def _is_blocked_address(address: ipaddress.IPv4Address | ipaddress.IPv6Address) -> bool:
    return any(
        (
            address.is_loopback,
            address.is_link_local,
            address.is_private,
            address.is_reserved,
            address.is_multicast,
            address.is_unspecified,
        )
    )
