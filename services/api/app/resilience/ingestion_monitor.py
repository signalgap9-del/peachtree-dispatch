"""Track per-provider ingestion health.

Every external data provider (NWS, Open-Meteo, OSRM, etc.) should record
success and failure events here.  The health() function produces a
snapshot suitable for the /health endpoint and the synthetic canary.
"""
from __future__ import annotations

import threading
from time import monotonic
from typing import TypedDict


class ProviderHealth(TypedDict):
    provider: str
    status: str  # "ok" | "stale" | "unknown"
    last_success_age_seconds: float | None
    consecutive_failures: int


class IngestionMonitor:
    """Thread-safe registry of provider ingestion timestamps."""

    def __init__(self, stale_threshold: float = 600.0) -> None:
        self.stale_threshold = stale_threshold
        self._lock = threading.Lock()
        self._last_success: dict[str, float] = {}
        self._consecutive_failures: dict[str, int] = {}

    def record_success(self, provider: str) -> None:
        with self._lock:
            self._last_success[provider] = monotonic()
            self._consecutive_failures[provider] = 0

    def record_failure(self, provider: str) -> None:
        with self._lock:
            self._consecutive_failures[provider] = (
                self._consecutive_failures.get(provider, 0) + 1
            )

    def health(self) -> dict[str, ProviderHealth]:
        """Return a health snapshot keyed by provider name."""
        now = monotonic()
        with self._lock:
            providers = sorted(
                set(self._last_success) | set(self._consecutive_failures)
            )
            result: dict[str, ProviderHealth] = {}
            for provider in providers:
                last = self._last_success.get(provider)
                age = (now - last) if last is not None else None
                failures = self._consecutive_failures.get(provider, 0)
                if age is None:
                    status = "unknown"
                elif age > self.stale_threshold:
                    status = "stale"
                else:
                    status = "ok"
                result[provider] = ProviderHealth(
                    provider=provider,
                    status=status,
                    last_success_age_seconds=round(age, 1) if age is not None else None,
                    consecutive_failures=failures,
                )
            return result

    def stale_providers(self) -> list[str]:
        """Return names of providers whose last success exceeds the threshold."""
        return [
            name
            for name, info in self.health().items()
            if info["status"] in ("stale", "unknown")
        ]

    def reset(self) -> None:
        with self._lock:
            self._last_success.clear()
            self._consecutive_failures.clear()
