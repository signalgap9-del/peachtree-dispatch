"""Provider-agnostic routing contracts shared by every routing backend."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol, Sequence

# Waypoints are (longitude, latitude) pairs, matching GeoJSON/OSRM ordering.
Waypoint = tuple[float, float]

METERS_PER_MILE = 1609.344


@dataclass(frozen=True)
class Route:
    """A single route alternative in provider-neutral units."""

    coordinates: list[list[float]]
    distance_meters: float
    duration_seconds: float
    # Free-flow (no traffic) duration, when the provider reports it.
    static_duration_seconds: float | None = None
    source_status: str = "LIVE"
    provider_name: str = ""

    @property
    def distance_miles(self) -> float:
        return self.distance_meters / METERS_PER_MILE

    @property
    def duration_minutes(self) -> float:
        return self.duration_seconds / 60


@dataclass(frozen=True)
class Matrix:
    """All-pairs duration/distance matrix indexed in waypoint order."""

    provider_name: str
    duration_seconds: list[list[float | None]]
    distance_meters: list[list[float | None]]
    source_status: str = "LIVE"


class RoutingProviderError(RuntimeError):
    """A routing provider request failed or returned unusable data."""

    def __init__(self, message: str, *, provider_name: str = "", status_code: int | None = None):
        super().__init__(message)
        self.provider_name = provider_name
        self.status_code = status_code


class RoutingProvider(Protocol):
    """Interface every routing backend implements.

    ``route`` returns alternatives ordered by estimated duration (a single
    route when alternatives are not requested/supported); ``distance_matrix``
    returns a square matrix whose rows/columns follow the waypoint order.
    """

    name: str

    def route(self, waypoints: Sequence[Waypoint], *, alternatives: bool = False) -> list[Route]:
        ...

    def distance_matrix(self, waypoints: Sequence[Waypoint]) -> Matrix:
        ...
