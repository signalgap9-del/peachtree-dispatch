from __future__ import annotations

import pytest

from app.vrp.edge_risk import ConstantEdgeRiskProvider
from app.vrp.matrix import FixtureMatrixProvider
from app.vrp.models import RoutingMatrix
from app.nl2opt.formulation import FormulationError, VrpFormulation, formulate_vrp
from app.nl2opt.constraint_translators import (
    TranslatorResult,
    translate_avoid_corridor,
    translate_capacity,
    translate_hazmat,
    translate_priority_stop,
    translate_time_window,
    translate_weather_deadline,
)


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

def _matrix(node_ids: list[str], durations: list[list[float]]) -> RoutingMatrix:
    size = len(node_ids)
    distances = [[d * 20.0 for d in row] for row in durations]
    return RoutingMatrix(
        provider="test-fixture",
        node_ids=node_ids,
        duration_seconds=durations,
        distance_meters=distances,
        source_status="ESTIMATED",
    )


class StubRiskDataProvider:
    def __init__(self, data: dict | None = None):
        self._data = data or {}

    def get_national_risk(self) -> dict:
        return self._data


def _formulate(
    constraints: dict,
    node_ids: list[str] | None = None,
    durations: list[list[float]] | None = None,
    risk_data: dict | None = None,
    risk_score: int = 10,
) -> VrpFormulation:
    stops = constraints.get("stops", [])
    count = len(stops) if stops else 2
    ids = node_ids or [f"n{i}" for i in range(count)]
    durs = durations or [[0 if i == j else 600 for j in range(count)] for i in range(count)]
    matrix = _matrix(ids, durs)
    return formulate_vrp(
        constraints=constraints,
        matrix_provider=FixtureMatrixProvider(matrix),
        edge_risk_provider=ConstantEdgeRiskProvider(risk_score),
        risk_data_provider=StubRiskDataProvider(risk_data),
    )


# ---------------------------------------------------------------------------
# Tests: basic formulation
# ---------------------------------------------------------------------------

def test_simple_a_to_b_with_time_window() -> None:
    constraints = {
        "stops": [
            {"name": "Atlanta", "type": "origin", "latitude": 33.749, "longitude": -84.388},
            {
                "name": "Savannah",
                "type": "destination",
                "latitude": 32.081,
                "longitude": -81.091,
                "timeWindow": {"earliest": "08:00", "latest": "17:00"},
            },
        ],
        "objective": "min_time",
    }
    result = _formulate(constraints)

    assert len(result.nodes) == 2
    assert result.nodes[0].label == "Atlanta"
    assert result.nodes[1].label == "Savannah"
    # Time window on stop 1: 08:00 = 28800s, 17:00 = 61200s
    assert result.time_windows[1] == (28800, 61200)
    assert result.objective == "min_time"


def test_multi_stop_with_priority_gets_earlier_bound() -> None:
    constraints = {
        "stops": [
            {"name": "Depot", "type": "origin", "latitude": 33.749, "longitude": -84.388},
            {"name": "Stop A", "type": "delivery", "latitude": 33.80, "longitude": -84.30, "priority": 5},
            {"name": "Stop B", "type": "delivery", "latitude": 33.85, "longitude": -84.25, "priority": 1},
        ],
    }
    result = _formulate(constraints)

    # Priority 5 stop should get a soft deadline (3h = 10800s)
    assert 1 in result.time_windows
    _, latest = result.time_windows[1]
    assert latest == 10800
    # Priority 1 stop should NOT get a priority deadline
    assert 2 not in result.time_windows or result.time_windows[2][1] != 10800


def test_avoid_corridor_adds_edge_penalty() -> None:
    constraints = {
        "stops": [
            {"name": "A", "type": "origin", "latitude": 33.749, "longitude": -84.388},
            {"name": "B", "type": "destination", "latitude": 34.0, "longitude": -84.0},
        ],
        "softConstraints": [
            {"type": "avoid_corridor", "target": "I-85", "weight": 0.8, "reason": "flooding"},
        ],
    }
    result = _formulate(constraints)

    # All non-diagonal edges should have penalty = 0.8 * 10000 = 8000
    assert result.edge_penalties[(0, 1)] == 8000
    assert result.edge_penalties[(1, 0)] == 8000
    # Cost matrix should reflect the penalty
    assert result.cost_matrix[0][1] > result.base_duration_matrix[0][1]
    assert any("I-85" in e for e in result.explanations)


def test_weather_deadline_sets_hard_bound() -> None:
    constraints = {
        "stops": [
            {"name": "A", "type": "origin", "latitude": 33.749, "longitude": -84.388},
            {"name": "B", "type": "delivery", "latitude": 34.0, "longitude": -84.0},
            {"name": "C", "type": "destination", "latitude": 34.1, "longitude": -83.9},
        ],
        "hardConstraints": [
            {"type": "weather_deadline", "target": "Hurricane Zeta", "reason": "storm approach"},
        ],
    }
    # Storm ETA = 240 minutes; deadline = 240*60 - 1800 = 12600s
    result = _formulate(constraints, risk_data={"storm_eta_minutes": 240})

    # Non-depot nodes (1, 2) should have a hard deadline
    assert result.time_windows[1][1] == 12600
    assert result.time_windows[2][1] == 12600
    assert any("Hurricane Zeta" in e for e in result.explanations)


def test_weather_deadline_without_risk_data_warns() -> None:
    constraints = {
        "stops": [
            {"name": "A", "type": "origin", "latitude": 33.749, "longitude": -84.388},
            {"name": "B", "type": "destination", "latitude": 34.0, "longitude": -84.0},
        ],
        "hardConstraints": [
            {"type": "weather_deadline", "target": "storm"},
        ],
    }
    result = _formulate(constraints, risk_data={})
    assert any("unavailable" in w for w in result.warnings)


def test_min_risk_vs_min_time_different_matrices() -> None:
    stops = [
        {"name": "A", "type": "origin", "latitude": 33.749, "longitude": -84.388},
        {"name": "B", "type": "destination", "latitude": 34.0, "longitude": -84.0},
    ]
    # Short base duration + high risk so the risk penalty dominates.
    durations = [[0, 60], [60, 0]]
    risk_result = _formulate(
        {"stops": stops, "objective": "min_risk"},
        durations=durations,
        risk_score=80,
    )
    time_result = _formulate(
        {"stops": stops, "objective": "min_time"},
        durations=durations,
        risk_score=80,
    )

    # With high risk weights and short base duration, min_risk costs more.
    assert risk_result.cost_matrix[0][1] > time_result.cost_matrix[0][1]
    assert risk_result.objective == "min_risk"
    assert time_result.objective == "min_time"


def test_invalid_constraint_type_graceful() -> None:
    constraints = {
        "stops": [
            {"name": "A", "type": "origin", "latitude": 33.749, "longitude": -84.388},
            {"name": "B", "type": "destination", "latitude": 34.0, "longitude": -84.0},
        ],
        "softConstraints": [
            {"type": "teleportation_bypass", "target": "zone-7", "weight": 0.5},
        ],
        "hardConstraints": [
            {"type": "warp_drive", "target": "sector-9"},
        ],
    }
    result = _formulate(constraints)
    # Should not raise; unknown types produce warnings
    assert any("teleportation_bypass" in w for w in result.warnings)
    assert any("warp_drive" in w for w in result.warnings)


def test_empty_constraints_raises() -> None:
    with pytest.raises(FormulationError, match="at least one stop"):
        _formulate({"stops": []})


def test_missing_stops_raises() -> None:
    with pytest.raises(FormulationError, match="at least one stop"):
        _formulate({})


def test_empty_constraints_default_formulation() -> None:
    """Minimal valid constraints produce a default balanced formulation."""
    constraints = {
        "stops": [
            {"name": "A", "latitude": 33.749, "longitude": -84.388},
            {"name": "B", "latitude": 34.0, "longitude": -84.0},
        ],
    }
    result = _formulate(constraints)
    assert result.objective == "balanced"
    assert len(result.nodes) == 2
    assert result.vehicle_capacities == [100]


# ---------------------------------------------------------------------------
# Tests: individual translators
# ---------------------------------------------------------------------------

def test_translate_time_window_parses_hhmm() -> None:
    result = translate_time_window(0, {"earliest": "06:30", "latest": "18:00"})
    assert result.time_bounds[0] == (23400, 64800)


def test_translate_time_window_none() -> None:
    result = translate_time_window(0, None)
    assert result.time_bounds == {}


def test_translate_capacity_with_value() -> None:
    result = translate_capacity({"type": "truck", "capacityKg": 5000}, 2)
    assert result.vehicle_capacities == [5000, 5000]


def test_translate_capacity_none_vehicle() -> None:
    result = translate_capacity(None, 1)
    assert result.vehicle_capacities == []


def test_translate_hazmat_warns() -> None:
    result = translate_hazmat({"type": "hazmat", "target": "Class 3"}, 3)
    assert len(result.warnings) == 1
    assert "Class 3" in result.warnings[0]


def test_translate_priority_stop_low_priority_no_bound() -> None:
    result = translate_priority_stop(1, 2, 5)
    assert result.time_bounds == {}


def test_translate_avoid_corridor_penalty_scale() -> None:
    matrix = [[0, 600], [600, 0]]
    result = translate_avoid_corridor({"target": "Highway 1", "weight": 1.0}, 2, matrix)
    assert result.edge_penalties[(0, 1)] == 10000
    assert result.edge_penalties[(1, 0)] == 10000
