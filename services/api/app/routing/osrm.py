"""OSRM routing provider.

This is the default backend for local development: it needs no API key and
talks to the public OSRM demo server (or a private OSRM instance via
OSRM_BASE_URL). The HTTP calls previously inlined in app.directions and
app.vrp.matrix live here so every consumer shares one OSRM client.
"""
from __future__ import annotations

import json
import os
from typing import Sequence
from urllib.parse import urlencode
from urllib.request import Request

from ..outbound_http import safe_urlopen as urlopen
from .provider import Matrix, Route, RoutingProviderError

DEFAULT_OSRM_BASE_URL = "https://router.project-osrm.org"
USER_AGENT = "AtmosPath route-engine/0.1"


class OsrmProvider:
    """Road routing via an OSRM HTTP server (route + table services)."""

    name = "osrm"

    def __init__(self, base_url: str | None = None, timeout_seconds: float | None = None):
        self.base_url = (base_url or os.environ.get("OSRM_BASE_URL") or DEFAULT_OSRM_BASE_URL).rstrip("/")
        if timeout_seconds is None:
            timeout_seconds = float(os.environ.get("OSRM_TIMEOUT_SECONDS", "12"))
        self.timeout_seconds = timeout_seconds

    def route(
        self,
        waypoints: Sequence[tuple[float, float]],
        *,
        alternatives: bool = False,
        steps: bool = False,
    ) -> list[Route]:
        if len(waypoints) < 2:
            raise ValueError("at least two waypoints are required for a route")
        coordinate_string = ";".join(f"{lon},{lat}" for lon, lat in waypoints)
        params = urlencode(
            {
                "overview": "full",
                "geometries": "geojson",
                "steps": "true" if steps else "false",
                "alternatives": "3" if alternatives else "false",
            }
        )
        request = Request(
            f"{self.base_url}/route/v1/driving/{coordinate_string}?{params}",
            headers={"User-Agent": USER_AGENT},
        )
        try:
            with urlopen(request, timeout=self.timeout_seconds) as response:
                payload = json.load(response)
        except Exception as exc:
            raise RoutingProviderError(f"OSRM route request failed: {exc}", provider_name=self.name) from exc
        routes = payload.get("routes")
        if not isinstance(routes, list):
            raise RoutingProviderError("OSRM route response did not contain routes", provider_name=self.name)
        return [
            Route(
                coordinates=route["geometry"]["coordinates"],
                distance_meters=float(route["distance"]),
                duration_seconds=float(route["duration"]),
                source_status="LIVE",
                provider_name=self.name,
            )
            for route in routes
        ]

    def distance_matrix(self, waypoints: Sequence[tuple[float, float]]) -> Matrix:
        if len(waypoints) < 2:
            raise ValueError("at least two waypoints are required for a routing matrix")
        coordinates = ";".join(f"{lon},{lat}" for lon, lat in waypoints)
        params = urlencode({"annotations": "duration,distance"})
        request = Request(
            f"{self.base_url}/table/v1/driving/{coordinates}?{params}",
            headers={"User-Agent": USER_AGENT},
        )
        try:
            with urlopen(request, timeout=self.timeout_seconds) as response:
                payload = json.load(response)
        except Exception as exc:
            raise RoutingProviderError(f"OSRM table request failed: {exc}", provider_name=self.name) from exc

        durations = payload.get("durations")
        distances = payload.get("distances")
        if not isinstance(durations, list) or not isinstance(distances, list):
            raise RoutingProviderError(
                "OSRM table response did not contain duration and distance matrices",
                provider_name=self.name,
            )
        status = "PARTIAL" if has_missing(durations) or has_missing(distances) else "LIVE"
        return Matrix(
            provider_name=self.name,
            duration_seconds=durations,
            distance_meters=distances,
            source_status=status,
        )


def has_missing(matrix: list[list[float | None]]) -> bool:
    return any(value is None for row in matrix for value in row)
