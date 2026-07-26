"""Shared test fixtures for the AtmosPath API test suite."""
from __future__ import annotations

import pytest

ROUTING_ENV_VARS = ("ROUTING_PROVIDER", "GOOGLE_ROUTES_API_KEY")


@pytest.fixture(autouse=True)
def _isolate_routing_provider_env(monkeypatch):
    """Keep routing provider selection deterministic across test runs.

    A developer shell may export GOOGLE_ROUTES_API_KEY or ROUTING_PROVIDER;
    tests that want a specific provider opt back in with monkeypatch.setenv.
    """
    for name in ROUTING_ENV_VARS:
        monkeypatch.delenv(name, raising=False)
