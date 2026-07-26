"""Tiered TTL cache with stale-while-revalidate for external provider responses.

Two TTL tiers control freshness:

* **fresh_ttl** – the entry is considered current and returned immediately.
* **stale_ttl** – the entry is stale but still usable.  A background
  revalidation is triggered (at most once per key) while the stale value is
  returned to the caller.

Beyond ``stale_ttl`` the entry is evicted and the caller blocks on a fresh
fetch.
"""
from __future__ import annotations

import threading
from time import monotonic
from typing import Any, Callable, TypeVar

T = TypeVar("T")


class _Entry:
    __slots__ = ("value", "created_at", "revalidating")

    def __init__(self, value: Any, created_at: float) -> None:
        self.value = value
        self.created_at = created_at
        self.revalidating = False


class ProviderCache:
    """Thread-safe tiered TTL cache.

    Parameters
    ----------
    fresh_ttl:
        Seconds an entry is considered fresh.
    stale_ttl:
        Total seconds an entry remains usable (fresh + stale window).
        Must be >= ``fresh_ttl``.
    max_entries:
        Upper bound on cache size; oldest entries are evicted first.
    """

    def __init__(
        self,
        fresh_ttl: float = 60.0,
        stale_ttl: float = 300.0,
        max_entries: int = 256,
    ) -> None:
        if stale_ttl < fresh_ttl:
            raise ValueError("stale_ttl must be >= fresh_ttl")
        self.fresh_ttl = fresh_ttl
        self.stale_ttl = stale_ttl
        self.max_entries = max_entries
        self._lock = threading.Lock()
        self._store: dict[str, _Entry] = {}

    def get_or_revalidate(
        self,
        key: str,
        fetcher: Callable[[], T],
    ) -> T:
        """Return a cached value or fetch a new one.

        * Fresh hit  → return immediately.
        * Stale hit  → return stale value, trigger one background revalidation.
        * Miss       → block on ``fetcher()`` and cache the result.
        """
        now = monotonic()

        with self._lock:
            entry = self._store.get(key)
            if entry is not None:
                age = now - entry.created_at
                if age <= self.fresh_ttl:
                    return entry.value  # type: ignore[return-value]
                if age <= self.stale_ttl:
                    if not entry.revalidating:
                        entry.revalidating = True
                        self._spawn_revalidation(key, fetcher)
                    return entry.value  # type: ignore[return-value]
                # Expired beyond stale window – fall through to fetch.
                del self._store[key]

        # Cache miss – synchronous fetch.
        value = fetcher()
        self._put(key, value)
        return value

    def put(self, key: str, value: Any) -> None:
        """Explicitly store a value (e.g. after a successful provider call)."""
        self._put(key, value)

    def get(self, key: str) -> Any | None:
        """Return the cached value if fresh or stale, else ``None``."""
        now = monotonic()
        with self._lock:
            entry = self._store.get(key)
            if entry is None:
                return None
            if now - entry.created_at > self.stale_ttl:
                del self._store[key]
                return None
            return entry.value

    def invalidate(self, key: str) -> None:
        with self._lock:
            self._store.pop(key, None)

    def clear(self) -> None:
        with self._lock:
            self._store.clear()

    # -- internals -----------------------------------------------------------

    def _put(self, key: str, value: Any) -> None:
        with self._lock:
            self._store[key] = _Entry(value, monotonic())
            while len(self._store) > self.max_entries:
                oldest_key = next(iter(self._store))
                del self._store[oldest_key]

    def _spawn_revalidation(self, key: str, fetcher: Callable[[], Any]) -> None:
        thread = threading.Thread(
            target=self._revalidate,
            args=(key, fetcher),
            daemon=True,
            name=f"cache-revalidate-{key}",
        )
        thread.start()

    def _revalidate(self, key: str, fetcher: Callable[[], Any]) -> None:
        try:
            value = fetcher()
            self._put(key, value)
        except Exception:
            # Revalidation failed; the stale entry remains until it expires.
            with self._lock:
                entry = self._store.get(key)
                if entry is not None:
                    entry.revalidating = False
