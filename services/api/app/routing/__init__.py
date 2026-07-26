"""Swappable routing providers.

OSRM is the default for local development (no key required); Google Routes
is the production provider, selected automatically when a key is configured
or explicitly via ROUTING_PROVIDER=google.
"""
from .factory import get_routing_provider
from .provider import Matrix, Route, RoutingProvider, RoutingProviderError, Waypoint

__all__ = [
    "Matrix",
    "Route",
    "RoutingProvider",
    "RoutingProviderError",
    "Waypoint",
    "get_routing_provider",
]
