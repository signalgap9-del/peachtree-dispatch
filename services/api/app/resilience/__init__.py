"""External-provider resilience layer.

Provides per-provider circuit breakers, a tiered response cache, and an
ingestion health monitor.  Call sites wrap ``safe_urlopen`` with
``resilient_urlopen`` to gain circuit-breaking and ingestion tracking
without modifying ``outbound_http.py``.
"""
from __future__ import annotations

from typing import Any
from urllib.request import Request

from ..outbound_http import safe_urlopen
from .circuit_breaker import CircuitBreaker, CircuitBreakerOpenError, CircuitState
from .ingestion_monitor import IngestionMonitor, ProviderHealth
from .provider_cache import ProviderCache

# -- shared singletons -------------------------------------------------------

ingestion_monitor = IngestionMonitor(stale_threshold=600.0)

provider_cache = ProviderCache(fresh_ttl=60.0, stale_ttl=300.0)

_breakers: dict[str, CircuitBreaker] = {
    "nws": CircuitBreaker("nws", failure_threshold=5, recovery_timeout=60.0),
    "open-meteo": CircuitBreaker("open-meteo", failure_threshold=5, recovery_timeout=30.0),
    "osrm": CircuitBreaker("osrm", failure_threshold=5, recovery_timeout=30.0),
    "nominatim": CircuitBreaker("nominatim", failure_threshold=5, recovery_timeout=30.0),
}


def get_breaker(provider: str) -> CircuitBreaker:
    """Return the circuit breaker for *provider*, creating one if needed."""
    breaker = _breakers.get(provider)
    if breaker is None:
        breaker = CircuitBreaker(provider)
        _breakers[provider] = breaker
    return breaker


def resilient_urlopen(
    provider: str,
    url_or_request: str | Request,
    *,
    timeout: float,
) -> Any:
    """Drop-in wrapper around ``safe_urlopen`` with circuit-breaking and
    ingestion tracking.

    When the breaker is closed this behaves identically to ``safe_urlopen``.
    When the breaker is open it raises ``CircuitBreakerOpenError`` (a
    ``RuntimeError`` subclass) which existing ``except Exception`` fallback
    handlers already catch.
    """
    breaker = get_breaker(provider)
    try:
        result = breaker.call(lambda: safe_urlopen(url_or_request, timeout=timeout))
    except CircuitBreakerOpenError:
        ingestion_monitor.record_failure(provider)
        raise
    except Exception:
        ingestion_monitor.record_failure(provider)
        raise
    else:
        ingestion_monitor.record_success(provider)
        return result


def reset_all_breakers() -> None:
    """Reset every registered breaker (useful in tests)."""
    for breaker in _breakers.values():
        breaker.reset()
    ingestion_monitor.reset()


__all__ = [
    "CircuitBreaker",
    "CircuitBreakerOpenError",
    "CircuitState",
    "IngestionMonitor",
    "ProviderCache",
    "ProviderHealth",
    "get_breaker",
    "ingestion_monitor",
    "provider_cache",
    "resilient_urlopen",
    "reset_all_breakers",
]
