"""Tests for the NL2Opt API endpoints (app.nl2opt.routes).

These exercise the FastAPI route handlers with the network-backed providers
swapped for deterministic fixtures, plus the OR-Tools solver adapter directly.
"""
from __future__ import annotations

from types import SimpleNamespace

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.nl2opt.formulation import FormulationError, formulate_vrp
from app.nl2opt.routes import (
    _DefaultRiskDataProvider,
    _solve_formulation,
    router,
)
from app.vrp.edge_risk import ConstantEdgeRiskProvider
from app.vrp.matrix import FixtureMatrixProvider, HaversineMatrixProvider
from app.vrp.models import RoutingMatrix


@pytest.fixture()
def client(monkeypatch) -> TestClient:
    """A TestClient wired to only the nl2opt router with network providers mocked."""
    monkeypatch.setattr(
        "app.nl2opt.routes.build_default_matrix_provider",
        lambda: HaversineMatrixProvider(),
    )
    monkeypatch.setattr(
        "app.nl2opt.routes.build_default_edge_risk_provider",
        lambda: ConstantEdgeRiskProvider(10),
    )
    application = FastAPI()
    application.include_router(router)
    return TestClient(application)


def _two_stop_constraints() -> dict:
    return {
        "stops": [
            {"name": "Atlanta", "type": "origin", "latitude": 33.749, "longitude": -84.388},
            {"name": "Savannah", "type": "destination", "latitude": 32.081, "longitude": -81.091},
        ],
        "objective": "min_time",
    }


# ---------------------------------------------------------------------------
# /nl2opt/formulate
# ---------------------------------------------------------------------------

def test_formulate_success_returns_formulation(client) -> None:
    response = client.post("/nl2opt/formulate", json={"constraints": _two_stop_constraints()})

    assert response.status_code == 200
    body = response.json()
    formulation = body["formulation"]
    assert len(formulation["nodes"]) == 2
    assert formulation["nodes"][0]["label"] == "Atlanta"
    assert formulation["objective"] == "min_time"
    assert formulation["metadata"]["node_count"] == 2


def test_formulate_empty_stops_returns_400(client) -> None:
    response = client.post("/nl2opt/formulate", json={"constraints": {"stops": []}})

    assert response.status_code == 400
    assert "at least one stop" in response.json()["detail"]


def test_formulate_invalid_coordinate_returns_422(client) -> None:
    constraints = {
        "stops": [
            {"name": "A", "type": "origin", "latitude": "not-a-number", "longitude": -84.388},
            {"name": "B", "type": "destination", "latitude": 34.0, "longitude": -84.0},
        ],
    }
    response = client.post("/nl2opt/formulate", json={"constraints": constraints})

    # float("not-a-number") raises a plain ValueError -> 422 (not FormulationError).
    assert response.status_code == 422


def test_formulate_missing_body_is_validation_error(client) -> None:
    response = client.post("/nl2opt/formulate", json={})
    assert response.status_code == 422


# ---------------------------------------------------------------------------
# /nl2opt/solve
# ---------------------------------------------------------------------------

def test_solve_success_runs_ortools(client) -> None:
    response = client.post("/nl2opt/solve", json={"constraints": _two_stop_constraints()})

    assert response.status_code == 200
    body = response.json()
    assert body["solver_status"] == "FEASIBLE"
    assert body["solve_time_ms"] >= 0
    assert body["formulation"]["objective"] == "min_time"
    assert isinstance(body["explanations"], list)
    # A 2-node round trip yields a route with vehicle-0 and at least the depot stops.
    assert body["routes"], "expected at least one routed vehicle"
    assert body["routes"][0]["vehicle_id"] == "vehicle-0"
    assert body["routes"][0]["stops"][0]["sequence"] == 1


def test_solve_empty_stops_returns_400(client) -> None:
    response = client.post("/nl2opt/solve", json={"constraints": {"stops": []}})
    assert response.status_code == 400


def test_solve_invalid_coordinate_returns_422(client) -> None:
    constraints = {
        "stops": [
            {"name": "A", "latitude": "bad", "longitude": -84.388},
            {"name": "B", "latitude": 34.0, "longitude": -84.0},
        ],
    }
    response = client.post("/nl2opt/solve", json={"constraints": constraints})
    assert response.status_code == 422


def test_solve_solver_failure_is_reported_as_error(client, monkeypatch) -> None:
    def boom(formulation):
        raise RuntimeError("solver exploded")

    monkeypatch.setattr("app.nl2opt.routes._solve_formulation", boom)

    response = client.post("/nl2opt/solve", json={"constraints": _two_stop_constraints()})

    assert response.status_code == 200
    body = response.json()
    assert body["solver_status"] == "ERROR"
    assert body["routes"] == []
    assert any("Solver error" in warning for warning in body["formulation"]["warnings"])


# ---------------------------------------------------------------------------
# _solve_formulation adapter (direct)
# ---------------------------------------------------------------------------

def _formulate(constraints: dict, risk_score: int = 10):
    stops = constraints["stops"]
    node_ids = [f"n{i}" for i in range(len(stops))]
    size = len(node_ids)
    durations = [[0.0 if i == j else 600.0 for j in range(size)] for i in range(size)]
    distances = [[d * 20.0 for d in row] for row in durations]
    matrix = RoutingMatrix(
        provider="test-fixture",
        node_ids=node_ids,
        duration_seconds=durations,
        distance_meters=distances,
        source_status="ESTIMATED",
    )
    return formulate_vrp(
        constraints=constraints,
        matrix_provider=FixtureMatrixProvider(matrix),
        edge_risk_provider=ConstantEdgeRiskProvider(risk_score),
    )


def test_solve_formulation_single_node_is_infeasible() -> None:
    formulation = _formulate({"stops": [{"name": "A", "latitude": 33.7, "longitude": -84.3}]})
    routes, status = _solve_formulation(formulation)
    assert routes == []
    assert status == "INFEASIBLE"


def test_solve_formulation_multi_stop_is_feasible() -> None:
    constraints = {
        "stops": [
            {"name": "Depot", "type": "origin", "latitude": 33.749, "longitude": -84.388},
            {"name": "Stop A", "type": "delivery", "latitude": 33.80, "longitude": -84.30},
            {"name": "Stop B", "type": "delivery", "latitude": 33.85, "longitude": -84.25},
        ],
    }
    formulation = _formulate(constraints)
    routes, status = _solve_formulation(formulation)

    assert status == "FEASIBLE"
    assert len(routes) == 1
    stops = routes[0]["stops"]
    # Depot -> two deliveries -> return to depot.
    assert stops[0]["node_id"] == "n0"
    assert stops[-1]["node_id"] == "n0"
    assert [stop["sequence"] for stop in stops] == list(range(1, len(stops) + 1))


def test_solve_formulation_applies_time_windows_and_edge_masks() -> None:
    constraints = {
        "stops": [
            {"name": "Depot", "type": "origin", "latitude": 33.749, "longitude": -84.388},
            {
                "name": "Stop A",
                "type": "delivery",
                "latitude": 33.80,
                "longitude": -84.30,
                "timeWindow": {"earliest": "08:00", "latest": "17:00"},
            },
            {"name": "Stop B", "type": "destination", "latitude": 33.85, "longitude": -84.25},
        ],
    }
    formulation = _formulate(constraints)
    # Force an edge mask so that branch is exercised.
    formulation.edge_masks.append((0, 1))

    routes, status = _solve_formulation(formulation)
    assert status == "FEASIBLE"
    assert 1 in formulation.time_windows


# ---------------------------------------------------------------------------
# _DefaultRiskDataProvider
# ---------------------------------------------------------------------------

def test_default_risk_data_provider_returns_storm_eta(monkeypatch) -> None:
    overview = SimpleNamespace(storm_eta_minutes=180)
    monkeypatch.setattr("app.risk.national_risk", lambda: overview)

    assert _DefaultRiskDataProvider().get_national_risk() == {"storm_eta_minutes": 180}


def test_default_risk_data_provider_swallows_failures(monkeypatch) -> None:
    def boom() -> dict:
        raise RuntimeError("risk service down")

    monkeypatch.setattr("app.risk.national_risk", boom)

    assert _DefaultRiskDataProvider().get_national_risk() == {}
