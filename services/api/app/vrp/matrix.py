from __future__ import annotations

import json
import math
import os
from typing import Protocol
from urllib.parse import urlencode
from urllib.request import Request
from uuid import uuid4

from .models import GeoNode, RoutingMatrix
from ..outbound_http import safe_urlopen as urlopen

MILES_PER_METER = 1 / 1609.344
DEFAULT_SPEED_MPH = 48
USER_AGENT = "AtmosPath route-engine/0.1"


class RoutingMatrixProvider(Protocol):
    def build_matrix(self, nodes: list[GeoNode]) -> RoutingMatrix:
        ...


class MatrixProviderError(RuntimeError):
    pass


class OsrmTableMatrixProvider:
    """OSRM Table API provider for road-duration and road-distance matrices."""

    def __init__(self, base_url: str = "https://router.project-osrm.org", timeout_seconds: float = 12.0):
        self.base_url = base_url.rstrip("/")
        self.timeout_seconds = timeout_seconds

    def build_matrix(self, nodes: list[GeoNode]) -> RoutingMatrix:
        if len(nodes) < 2:
            raise ValueError("at least two nodes are required for a routing matrix")
        coordinates = ";".join(f"{node.longitude},{node.latitude}" for node in nodes)
        params = urlencode({"annotations": "duration,distance"})
        request = Request(
            f"{self.base_url}/table/v1/driving/{coordinates}?{params}",
            headers={"User-Agent": USER_AGENT},
        )
        try:
            with urlopen(request, timeout=self.timeout_seconds) as response:
                payload = json.load(response)
        except Exception as exc:
            raise MatrixProviderError("OSRM table request failed") from exc

        durations = payload.get("durations")
        distances = payload.get("distances")
        if not isinstance(durations, list) or not isinstance(distances, list):
            raise MatrixProviderError("OSRM table response did not contain duration and distance matrices")

        status = "PARTIAL" if _has_missing(durations) or _has_missing(distances) else "LIVE"
        return RoutingMatrix(
            provider="osrm-table",
            node_ids=[node.node_id for node in nodes],
            duration_seconds=durations,
            distance_meters=distances,
            source_status=status,
            provider_request_id=f"osrm_{uuid4().hex[:12]}",
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
    osrm_base_url = os.environ.get("OSRM_BASE_URL", "https://router.project-osrm.org")
    timeout_seconds = float(os.environ.get("OSRM_TIMEOUT_SECONDS", "12"))
    return FallbackMatrixProvider(
        primary=OsrmTableMatrixProvider(base_url=osrm_base_url, timeout_seconds=timeout_seconds),
        fallback=HaversineMatrixProvider(),
    )


def _has_missing(matrix: list[list[float | None]]) -> bool:
    return any(value is None for row in matrix for value in row)


def _haversine_meters(origin: GeoNode, destination: GeoNode) -> float:
    radius_meters = 6_371_000
    lat1 = math.radians(origin.latitude)
    lat2 = math.radians(destination.latitude)
    delta_lat = math.radians(destination.latitude - origin.latitude)
    delta_lon = math.radians(destination.longitude - origin.longitude)
    a = math.sin(delta_lat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(delta_lon / 2) ** 2
    return radius_meters * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))
