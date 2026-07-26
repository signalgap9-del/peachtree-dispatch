"""Thread-safe circuit breaker for external provider calls.

States:
  CLOSED    – requests flow normally; failures are counted.
  OPEN      – requests are rejected immediately with ``CircuitBreakerOpenError``.
  HALF_OPEN – a limited number of probe requests are allowed through to test
              whether the provider has recovered.

Usage::

    breaker = CircuitBreaker("nws", failure_threshold=5, recovery_timeout=60)
    response = breaker.call(lambda: safe_urlopen(url, timeout=10))
"""
from __future__ import annotations

import enum
import threading
from time import monotonic
from typing import Any, Callable, TypeVar

T = TypeVar("T")


class CircuitState(enum.Enum):
    CLOSED = "closed"
    OPEN = "open"
    HALF_OPEN = "half_open"


class CircuitBreakerOpenError(RuntimeError):
    """Raised when a call is attempted while the breaker is open."""

    def __init__(self, breaker_name: str, retry_after: float) -> None:
        self.breaker_name = breaker_name
        self.retry_after = retry_after
        super().__init__(
            f"circuit breaker '{breaker_name}' is open; retry in {retry_after:.0f}s"
        )


class CircuitBreaker:
    """Per-provider circuit breaker.

    Parameters
    ----------
    name:
        Human-readable provider label (e.g. ``"nws"``, ``"open-meteo"``).
    failure_threshold:
        Consecutive failures before the breaker opens.
    recovery_timeout:
        Seconds to wait in the OPEN state before transitioning to HALF_OPEN.
    half_open_max_calls:
        Number of probe calls allowed through in the HALF_OPEN state.
    """

    def __init__(
        self,
        name: str,
        failure_threshold: int = 5,
        recovery_timeout: float = 60.0,
        half_open_max_calls: int = 1,
    ) -> None:
        self.name = name
        self.failure_threshold = failure_threshold
        self.recovery_timeout = recovery_timeout
        self.half_open_max_calls = half_open_max_calls

        self._lock = threading.Lock()
        self._state = CircuitState.CLOSED
        self._failure_count = 0
        self._success_count = 0
        self._half_open_calls = 0
        self._opened_at: float = 0.0
        self._last_failure_time: float = 0.0

    # -- public API ----------------------------------------------------------

    @property
    def state(self) -> CircuitState:
        with self._lock:
            self._maybe_transition_to_half_open()
            return self._state

    def call(self, fn: Callable[..., T], *args: Any, **kwargs: Any) -> T:
        """Execute *fn* through the breaker.

        Raises ``CircuitBreakerOpenError`` when the breaker is open.
        """
        with self._lock:
            self._maybe_transition_to_half_open()

            if self._state is CircuitState.OPEN:
                retry_after = self.recovery_timeout - (monotonic() - self._opened_at)
                raise CircuitBreakerOpenError(self.name, max(0.0, retry_after))

            if self._state is CircuitState.HALF_OPEN:
                if self._half_open_calls >= self.half_open_max_calls:
                    retry_after = self.recovery_timeout - (monotonic() - self._opened_at)
                    raise CircuitBreakerOpenError(self.name, max(0.0, retry_after))
                self._half_open_calls += 1

        try:
            result = fn(*args, **kwargs)
        except Exception:
            self.record_failure()
            raise
        else:
            self.record_success()
            return result

    def record_success(self) -> None:
        with self._lock:
            if self._state is CircuitState.HALF_OPEN:
                self._success_count += 1
                if self._success_count >= self.half_open_max_calls:
                    self._reset()
            else:
                self._failure_count = 0

    def record_failure(self) -> None:
        with self._lock:
            self._last_failure_time = monotonic()
            if self._state is CircuitState.HALF_OPEN:
                self._trip()
            else:
                self._failure_count += 1
                if self._failure_count >= self.failure_threshold:
                    self._trip()

    def reset(self) -> None:
        """Manually reset the breaker to CLOSED (useful in tests)."""
        with self._lock:
            self._reset()

    # -- internals -----------------------------------------------------------

    def _trip(self) -> None:
        """Transition to OPEN.  Caller must hold ``_lock``."""
        self._state = CircuitState.OPEN
        self._opened_at = monotonic()
        self._half_open_calls = 0
        self._success_count = 0

    def _reset(self) -> None:
        """Transition to CLOSED.  Caller must hold ``_lock``."""
        self._state = CircuitState.CLOSED
        self._failure_count = 0
        self._success_count = 0
        self._half_open_calls = 0

    def _maybe_transition_to_half_open(self) -> None:
        """Move from OPEN to HALF_OPEN if the recovery timeout has elapsed.
        Caller must hold ``_lock``.
        """
        if self._state is CircuitState.OPEN:
            if monotonic() - self._opened_at >= self.recovery_timeout:
                self._state = CircuitState.HALF_OPEN
                self._half_open_calls = 0
                self._success_count = 0

    def __repr__(self) -> str:
        return (
            f"CircuitBreaker(name={self.name!r}, state={self.state.value}, "
            f"failures={self._failure_count}/{self.failure_threshold})"
        )
