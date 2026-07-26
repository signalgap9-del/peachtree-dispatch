"""Tests for the circuit breaker state machine."""
import threading
import time
from unittest.mock import patch

import pytest

from app.resilience.circuit_breaker import (
    CircuitBreaker,
    CircuitBreakerOpenError,
    CircuitState,
)


@pytest.fixture()
def breaker():
    return CircuitBreaker("test", failure_threshold=3, recovery_timeout=0.3)


class TestClosedState:
    def test_starts_closed(self, breaker):
        assert breaker.state is CircuitState.CLOSED

    def test_successful_calls_stay_closed(self, breaker):
        for _ in range(10):
            result = breaker.call(lambda: 42)
            assert result == 42
        assert breaker.state is CircuitState.CLOSED

    def test_failures_below_threshold_stay_closed(self, breaker):
        for _ in range(2):
            with pytest.raises(ValueError):
                breaker.call(_raise_value_error)
        assert breaker.state is CircuitState.CLOSED

    def test_success_resets_failure_count(self, breaker):
        for _ in range(2):
            with pytest.raises(ValueError):
                breaker.call(_raise_value_error)
        breaker.call(lambda: "ok")
        # Two more failures should not trip (count was reset)
        for _ in range(2):
            with pytest.raises(ValueError):
                breaker.call(_raise_value_error)
        assert breaker.state is CircuitState.CLOSED


class TestOpenState:
    def test_opens_after_threshold_failures(self, breaker):
        for _ in range(3):
            with pytest.raises(ValueError):
                breaker.call(_raise_value_error)
        assert breaker.state is CircuitState.OPEN

    def test_rejects_calls_while_open(self, breaker):
        _trip_breaker(breaker)
        with pytest.raises(CircuitBreakerOpenError) as exc_info:
            breaker.call(lambda: 42)
        assert exc_info.value.breaker_name == "test"

    def test_open_error_is_runtime_error(self, breaker):
        _trip_breaker(breaker)
        with pytest.raises(RuntimeError):
            breaker.call(lambda: 42)


class TestHalfOpenState:
    def test_transitions_to_half_open_after_timeout(self, breaker):
        _trip_breaker(breaker)
        assert breaker.state is CircuitState.OPEN
        time.sleep(0.35)
        assert breaker.state is CircuitState.HALF_OPEN

    def test_half_open_allows_probe_call(self, breaker):
        _trip_breaker(breaker)
        time.sleep(0.35)
        result = breaker.call(lambda: "recovered")
        assert result == "recovered"

    def test_half_open_closes_on_success(self, breaker):
        _trip_breaker(breaker)
        time.sleep(0.35)
        breaker.call(lambda: "ok")
        assert breaker.state is CircuitState.CLOSED

    def test_half_open_reopens_on_failure(self, breaker):
        _trip_breaker(breaker)
        time.sleep(0.35)
        with pytest.raises(ValueError):
            breaker.call(_raise_value_error)
        assert breaker.state is CircuitState.OPEN

    def test_half_open_limits_concurrent_probes(self):
        breaker = CircuitBreaker("probe", failure_threshold=1, recovery_timeout=0.3, half_open_max_calls=1)
        _trip_breaker(breaker)
        time.sleep(0.35)
        # First probe occupies the single half-open slot
        entered = threading.Event()
        release = threading.Event()
        results = []

        def slow_call():
            entered.set()
            release.wait(timeout=2)
            return "ok"

        def probe():
            try:
                results.append(breaker.call(slow_call))
            except CircuitBreakerOpenError:
                results.append("rejected")

        t1 = threading.Thread(target=probe)
        t1.start()
        entered.wait(timeout=2)  # wait until first probe is inside the call
        # Second probe should be rejected because the slot is occupied
        t2 = threading.Thread(target=probe)
        t2.start()
        t2.join(timeout=3)
        release.set()
        t1.join(timeout=3)
        assert "rejected" in results


class TestThreadSafety:
    def test_concurrent_failures_do_not_corrupt_state(self):
        breaker = CircuitBreaker("concurrent", failure_threshold=10, recovery_timeout=60)
        errors = []

        def fail():
            try:
                breaker.call(_raise_value_error)
            except (ValueError, CircuitBreakerOpenError):
                pass
            except Exception as exc:
                errors.append(exc)

        threads = [threading.Thread(target=fail) for _ in range(20)]
        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=5)

        assert not errors
        assert breaker.state is CircuitState.OPEN


class TestManualReset:
    def test_reset_returns_to_closed(self, breaker):
        _trip_breaker(breaker)
        assert breaker.state is CircuitState.OPEN
        breaker.reset()
        assert breaker.state is CircuitState.CLOSED
        assert breaker.call(lambda: "ok") == "ok"


# -- helpers -----------------------------------------------------------------

def _raise_value_error():
    raise ValueError("boom")


def _trip_breaker(breaker: CircuitBreaker) -> None:
    for _ in range(breaker.failure_threshold):
        try:
            breaker.call(_raise_value_error)
        except ValueError:
            pass
