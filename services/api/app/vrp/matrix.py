from __future__ import annotations

import math
from typing import Protocol
from uuid import uuid4

from ..routing.factory import get_routing_provider
from ..routing.osrm import DEFAULT_OSRM_BASE_URL, OsrmProvider
from ..routing.osrm import has_missing as _has_missing  # noqa: F401  (re-exported for callers/tests)
from ..routing.provider import Matrix as ProviderMatrix
from ..routing.provider import RoutingProvider
from .models import GeoNode, RoutingMatrix

MILES_PER_METER = 1 / 1609.344
DEFAULT_SPEED_MPH = 48


class RoutingMatrixProvider(Protocol):
    def build_matrix(self, nodes: list[GeoNode]) -> RoutingMatrix:
        ...


class MatrixProviderError(RuntimeError):
    pass


class OsrmTableMatrixProvider:
    """OSRM Table API provider for road-duration and road-distance matrices.

    Thin VRP adapter over :class:`app.routing.osrm.OsrmProvider` that maps the
    provider-neutral matrix into the pydantic ``RoutingMatrix`` contract and
    preserves the historical ``osrm-table`` provider label.
    """

    def __init__(self, base_url: str = DEFAULT_OSRM_BASE_URL, timeout_seconds: float = 12.0):
        self._osrm = OsrmProvider(base_url=base_url, timeout_seconds=timeout_seconds)

    def build_matrix(self, nodes: list[GeoNode]) -> RoutingMatrix:
        if len(nodes) < 2:
            raise ValueError("at least two nodes are required for a routing matrix")
        try:
            matrix = self._osrm.distance_matrix([(node.longitude, node.latitude) for node in nodes])
        except Exception as exc:
            raise MatrixProviderError(str(exc)) from exc
        return _to_routing_matrix(matrix, nodes, provider_label="osrm-table", request_prefix="osrm")


class RoutingProviderMatrixAdapter:
    """Adapts any ``routing.RoutingProvider`` to the VRP matrix protocol.

    This is how non-OSRM backends (e.g. Google Routes) plug into the VRP
    solver without the solver knowing which backend is active.
    """

    def __init__(self, provider: RoutingProvider):
        self.provider = provider

    def build_matrix(self, nodes: list[GeoNode]) -> RoutingMatrix:
        if len(nodes) < 2:
            raise ValueError("at least two nodes are required for a routing matrix")
        try:
            matrix = self.provider.distance_matrix([(node.longitude, node.latitude) for node in nodes])
        except Exception as exc:
            raise MatrixProviderError(str(exc)) from exc
        return _to_routing_matrix(
            matrix,
            nodes,
            provider_label=matrix.provider_name,
            request_prefix=matrix.provider_name,
        )


def _to_routing_matrix(
    matrix: ProviderMatrix,
    nodes: list[GeoNode],
    *,
    provider_label: str,
    request_prefix: str,
) -> RoutingMatrix:
    status = (
        "PARTIAL"
        if _has_missing(matrix.duration_seconds) or _has_missing(matrix.distance_meters)
        else "LIVE"
    )
    return RoutingMatrix(
        provider=provider_label,
        node_ids=[node.node_id for node in nodes],
        duration_seconds=matrix.duration_seconds,
        distance_meters=matrix.distance_meters,
        source_status=status,
        provider_request_id=f"{request_prefix}_{uuid4().hex[:12]}",
    )


class HaversineMatrixProvider:
    """Deterministic fallback matrix.

    The fallback is intentionally marked ESTIMATED so UI/API consumers never
    confuse it with a live road network matrix.
    """

    def __init__(self, speed_mph: float = DEFAULT_SPEED_MPH):
        self.speed_mph = speed_mph

    def build_matrix(self, nodes: list[GeoNode]) -> RoutingMatrix:
        if len(nodes) < 2:
            raise ValueError("at least two nodes are required for a routing matrix")
        duration_rows: list[list[float]] = []
        distance_rows: list[list[float]] = []
        meters_per_second = max(self.speed_mph, 1) * 1609.344 / 3600
        for origin in nodes:
            duration_row: list[float] = []
            distance_row: list[float] = []
            for destination in nodes:
                meters = 0.0 if origin.node_id == destination.node_id else _haversine_meters(origin, destination)
                distance_row.append(round(meters, 1))
                duration_row.append(round(meters / meters_per_second, 1))
            duration_rows.append(duration_row)
            distance_rows.append(distance_row)
        return RoutingMatrix(
            provider="haversine-estimate",
            node_ids=[node.node_id for node in nodes],
            duration_seconds=duration_rows,
            distance_meters=distance_rows,
            source_status="ESTIMATED",
            provider_request_id=f"estimated_{uuid4().hex[:12]}",
        )


class FallbackMatrixProvider:
    def __init__(self, primary: RoutingMatrixProvider, fallback: RoutingMatrixProvider):
        self.primary = primary
        self.fallback = fallback

    def build_matrix(self, nodes: list[GeoNode]) -> RoutingMatrix:
        try:
            return self.primary.build_matrix(nodes)
        except Exception:
            return self.fallback.build_matrix(nodes)


class FixtureMatrixProvider:
    def __init__(self, matrix: RoutingMatrix):
        self.matrix = matrix

    def build_matrix(self, nodes: list[GeoNode]) -> RoutingMatrix:
        expected = [node.node_id for node in nodes]
        if expected != self.matrix.node_ids:
            raise ValueError(f"fixture node order mismatch: expected {self.matrix.node_ids}, got {expected}")
        return self.matrix


def build_default_matrix_provider() -> RoutingMatrixProvider:
    """VRP matrix provider wired through the routing provider factory.

    OSRM keeps its dedicated adapter (identical labels and request ids as
    before); any other backend flows through the generic adapter. A
    deterministic haversine fallback guarantees the solver always gets a
    matrix even when the live provider is down.
    """
    provider = get_routing_provider()
    primary: RoutingMatrixProvider
    if isinstance(provider, OsrmProvider):
        primary = OsrmTableMatrixProvider(
            base_url=provider.base_url, timeout_seconds=provider.timeout_seconds
        )
    else:
        primary = RoutingProviderMatrixAdapter(provider)
    return FallbackMatrixProvider(
        primary=primary,
        fallback=HaversineMatrixProvider(),
    )


def _haversine_meters(origin: GeoNode, destination: GeoNode) -> float:
    radius_meters = 6_371_000
    lat1 = math.radians(origin.latitude)
    lat2 = math.radians(destination.latitude)
    delta_lat = math.radians(destination.latitude - origin.latitude)
    delta_lon = math.radians(destination.longitude - origin.longitude)
    a = math.sin(delta_lat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(delta_lon / 2) ** 2
    return radius_meters * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))
