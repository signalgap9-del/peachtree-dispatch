"""Selects the active routing provider from environment configuration.

Selection rules:
- ``ROUTING_PROVIDER=google`` -> Google Routes. Falls back to OSRM with a
  logged warning when ``GOOGLE_ROUTES_API_KEY`` is missing, so a misconfigured
  deployment degrades to the free backend instead of failing.
- ``ROUTING_PROVIDER`` unset -> Google Routes when ``GOOGLE_ROUTES_API_KEY``
  is set, otherwise OSRM (the local development default).
- ``ROUTING_PROVIDER=osrm`` -> OSRM, even if a Google key is present.
"""
from __future__ import annotations

import logging
import os

from .google_routes import GoogleRoutesProvider
from .osrm import OsrmProvider
from .provider import RoutingProvider

logger = logging.getLogger(__name__)

GOOGLE = "google"
OSRM = "osrm"


def get_routing_provider() -> RoutingProvider:
    requested = os.environ.get("ROUTING_PROVIDER", "").strip().lower()
    api_key = os.environ.get("GOOGLE_ROUTES_API_KEY", "").strip()

    if requested == GOOGLE or (not requested and api_key):
        if not api_key:
            logger.warning(
                "ROUTING_PROVIDER=google requested but GOOGLE_ROUTES_API_KEY is not set; "
                "falling back to OSRM"
            )
            return OsrmProvider()
        return GoogleRoutesProvider(api_key=api_key)

    if requested and requested != OSRM:
        logger.warning("Unknown ROUTING_PROVIDER %r; falling back to OSRM", requested)
    return OsrmProvider()
