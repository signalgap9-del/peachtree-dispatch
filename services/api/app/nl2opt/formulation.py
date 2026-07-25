from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Any, Protocol

from ..vrp.cost_model import build_risk_adjusted_matrix
from ..vrp.edge_risk import EdgeRiskProvider
from ..vrp.matrix import RoutingMatrixProvider
from ..vrp.models import CostModelConfig, GeoNode, RoutingMatrix
from .constraint_translators import (
    TranslatorResult,
    translate_avoid_corridor,
    translate_capacity,
    translate_hazmat,
    translate_priority_stop,
    translate_time_window,
    translate_weather_deadline,
)

logger = logging.getLogger(__name__)

DEFAULT_VEHICLE_COUNT = 1
DEFAULT_CAPACITY = 100
DEFAULT_MAX_TIME_SECONDS = 12 * 3600


class RiskDataProvider(Protocol):
    """Provides national risk data (storm ETA, etc.) for weather deadlines."""

    def get_national_risk(self) -> dict[str, Any]:
        ...


@dataclass
class FormulationNode:
    """A node in the formulated VRP instance."""

    node_id: str
    label: str
    latitude: float
    longitude: float
    stop_type: str = "service"
    priority: int = 0


@dataclass
class VrpFormulation:
    """Complete solver-ready formulation translated from NL constraints."""

    nodes: list[FormulationNode]
    cost_matrix: list[list[int]]
    base_duration_matrix: list[list[float]]
    time_windows: dict[int, tuple[int | None, int | None]] = field(default_factory=dict)
    vehicle_capacities: list[int] = field(default_factory=list)
    edge_penalties: dict[tuple[int, int], int] = field(default_factory=dict)
    edge_masks: list[tuple[int, int]] = field(default_factory=list)
    objective: str = "balanced"
    max_time_seconds: int = DEFAULT_MAX_TIME_SECONDS
    explanations: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    metadata: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        """Serialise to a JSON-compatible dict for the API response."""
        return {
            "nodes": [
                {
                    "node_id": n.node_id,
                    "label": n.label,
                    "latitude": n.latitude,
                    "longitude": n.longitude,
                    "stop_type": n.stop_type,
                    "priority": n.priority,
                }
                for n in self.nodes
            ],
            "cost_matrix": self.cost_matrix,
            "base_duration_matrix": self.base_duration_matrix,
            "time_windows": {str(k): list(v) for k, v in self.time_windows.items()},
            "vehicle_capacities": self.vehicle_capacities,
            "edge_penalties": {f"{i},{j}": p for (i, j), p in self.edge_penalties.items()},
            "edge_masks": [list(m) for m in self.edge_masks],
            "objective": self.objective,
            "max_time_seconds": self.max_time_seconds,
            "explanations": self.explanations,
            "warnings": self.warnings,
            "metadata": self.metadata,
        }


class FormulationError(ValueError):
    """Raised when constraints cannot be translated into a valid formulation."""


def formulate_vrp(
    constraints: dict[str, Any],
    matrix_provider: RoutingMatrixProvider,
    edge_risk_provider: EdgeRiskProvider,
    risk_data_provider: RiskDataProvider | None = None,
) -> VrpFormulation:
    """Translate VrpConstraints JSON into a solver-ready VrpFormulation.

    Steps:
    1. Parse stops into a node list with coordinates.
    2. Parse time windows into OR-Tools dimension bounds.
    3. Parse vehicle constraints (capacity, hazmat).
    4. Parse soft constraints into penalty weights on edges.
    5. Parse hard constraints into infeasible edge masks.
    6. Inject weather risk edge costs via the existing cost model.
    7. Build the objective function based on ``constraints.objective``.
    """
    stops = constraints.get("stops")
    if not stops:
        raise FormulationError("constraints must include at least one stop")

    # --- Step 1: Parse stops -> nodes ----------------------------------------
    nodes, geo_nodes = _parse_stops(stops)
    node_count = len(nodes)
    explanations: list[str] = []
    warnings: list[str] = []

    # --- Build base routing matrix -------------------------------------------
    routing_matrix = matrix_provider.build_matrix(geo_nodes)
    base_duration = [
        [float(v) if v is not None else 0.0 for v in row]
        for row in routing_matrix.duration_seconds
    ]

    # --- Step 6: Risk-adjusted cost matrix -----------------------------------
    objective = _normalise_objective(constraints.get("objective"))
    cost_config = _cost_config_for_objective(objective)
    adjusted_matrix, _edge_costs = build_risk_adjusted_matrix(
        matrix=routing_matrix,
        nodes=geo_nodes,
        config=cost_config,
        edge_risk_provider=edge_risk_provider,
    )
    explanations.append(f"Objective '{objective}': cost matrix built with {cost_config.weather_risk_weight:.0%} weather weight.")

    # --- Step 2: Time windows ------------------------------------------------
    time_windows: dict[int, tuple[int | None, int | None]] = {}
    for idx, stop in enumerate(stops):
        tw = stop.get("timeWindow")
        result = translate_time_window(idx, tw)
        _merge_result(result, time_windows=time_windows, explanations=explanations, warnings=warnings)

    # Departure / arrival constraints
    departure = constraints.get("departure")
    if departure and departure.get("time"):
        from .constraint_translators import _parse_hhmm

        dep_seconds = _parse_hhmm(departure["time"])
        if dep_seconds is not None:
            existing = time_windows.get(0, (None, None))
            time_windows[0] = (dep_seconds, existing[1])
            flexibility = departure.get("flexibility", "strict")
            explanations.append(f"Departure at {departure['time']} ({flexibility}).")

    arrival = constraints.get("arrival")
    if arrival and arrival.get("time"):
        from .constraint_translators import _parse_hhmm

        arr_seconds = _parse_hhmm(arrival["time"])
        if arr_seconds is not None:
            last_idx = node_count - 1
            existing = time_windows.get(last_idx, (None, None))
            time_windows[last_idx] = (existing[0], arr_seconds)
            flexibility = arrival.get("flexibility", "strict")
            explanations.append(f"Arrival by {arrival['time']} ({flexibility}).")

    # --- Step 2b: Priority stops ---------------------------------------------
    for idx, stop in enumerate(stops):
        priority = int(stop.get("priority", 0))
        result = translate_priority_stop(idx, priority, node_count)
        _merge_result(result, time_windows=time_windows, explanations=explanations, warnings=warnings)

    # --- Step 3: Vehicle constraints -----------------------------------------
    vehicle = constraints.get("vehicle")
    vehicle_count = DEFAULT_VEHICLE_COUNT
    cap_result = translate_capacity(vehicle, vehicle_count)
    vehicle_capacities = cap_result.vehicle_capacities or [DEFAULT_CAPACITY] * vehicle_count
    explanations.append(cap_result.explanation)
    warnings.extend(cap_result.warnings)

    # --- Step 4: Soft constraints -> edge penalties --------------------------
    edge_penalties: dict[tuple[int, int], int] = {}
    for soft in constraints.get("softConstraints") or []:
        ctype = soft.get("type", "")
        if ctype == "avoid_corridor":
            result = translate_avoid_corridor(soft, node_count, adjusted_matrix)
        elif ctype == "weather_deadline":
            risk_data = risk_data_provider.get_national_risk() if risk_data_provider else None
            result = translate_weather_deadline(soft, risk_data, node_count)
        elif ctype == "priority_stop":
            # Handled at stop level; record explanation.
            result = TranslatorResult(explanation=f"Soft priority_stop '{soft.get('target', '')}' noted.")
        else:
            result = TranslatorResult(
                explanation=f"Unrecognised soft constraint type '{ctype}'; ignored.",
                warnings=[f"Unknown soft constraint type: {ctype}"],
            )
        _merge_result(result, edge_penalties=edge_penalties, time_windows=time_windows, explanations=explanations, warnings=warnings)

    # --- Step 5: Hard constraints -> edge masks ------------------------------
    edge_masks: list[tuple[int, int]] = []
    for hard in constraints.get("hardConstraints") or []:
        ctype = hard.get("type", "")
        if ctype == "hazmat":
            result = translate_hazmat(hard, node_count)
        elif ctype == "weather_deadline":
            risk_data = risk_data_provider.get_national_risk() if risk_data_provider else None
            result = translate_weather_deadline(hard, risk_data, node_count)
        else:
            result = TranslatorResult(
                explanation=f"Unrecognised hard constraint type '{ctype}'; ignored.",
                warnings=[f"Unknown hard constraint type: {ctype}"],
            )
        _merge_result(result, edge_masks=edge_masks, time_windows=time_windows, explanations=explanations, warnings=warnings)

    # --- Apply edge penalties to cost matrix ---------------------------------
    final_matrix = _apply_penalties(adjusted_matrix, edge_penalties)

    # --- Step 7: Assemble formulation ----------------------------------------
    return VrpFormulation(
        nodes=nodes,
        cost_matrix=final_matrix,
        base_duration_matrix=base_duration,
        time_windows=time_windows,
        vehicle_capacities=vehicle_capacities,
        edge_penalties=edge_penalties,
        edge_masks=edge_masks,
        objective=objective,
        explanations=explanations,
        warnings=warnings,
        metadata={
            "node_count": node_count,
            "vehicle_count": vehicle_count,
            "matrix_source": routing_matrix.source_status,
            "matrix_provider": routing_matrix.provider,
        },
    )


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

def _parse_stops(stops: list[dict]) -> tuple[list[FormulationNode], list[GeoNode]]:
    """Convert raw stop dicts into FormulationNodes and GeoNodes.

    Stops without explicit coordinates receive deterministic placeholder
    positions so the matrix provider can still produce a haversine estimate.
    """
    nodes: list[FormulationNode] = []
    geo_nodes: list[GeoNode] = []
    for idx, stop in enumerate(stops):
        name = stop.get("name", f"stop-{idx}")
        stop_type = stop.get("type", "service")
        priority = int(stop.get("priority", 0))
        lat = float(stop.get("latitude", 33.749 + idx * 0.01))
        lon = float(stop.get("longitude", -84.388 + idx * 0.01))
        node_id = f"n{idx}"

        nodes.append(FormulationNode(
            node_id=node_id,
            label=name,
            latitude=lat,
            longitude=lon,
            stop_type=stop_type,
            priority=priority,
        ))
        geo_nodes.append(GeoNode(node_id=node_id, label=name, latitude=lat, longitude=lon))
    return nodes, geo_nodes


def _normalise_objective(raw: str | None) -> str:
    if not raw:
        return "balanced"
    normalised = raw.strip().lower().replace("-", "_")
    if normalised in ("min_risk", "min_time", "balanced"):
        return normalised
    return "balanced"


def _cost_config_for_objective(objective: str) -> CostModelConfig:
    if objective == "min_risk":
        return CostModelConfig(
            duration_weight=0.3,
            weather_risk_weight=0.50,
            traffic_risk_weight=0.30,
            flood_risk_weight=0.45,
            alert_risk_weight=0.60,
        )
    if objective == "min_time":
        return CostModelConfig(
            duration_weight=1.0,
            weather_risk_weight=0.05,
            traffic_risk_weight=0.05,
            flood_risk_weight=0.05,
            alert_risk_weight=0.05,
        )
    # balanced (default)
    return CostModelConfig()


def _apply_penalties(
    matrix: list[list[int]],
    penalties: dict[tuple[int, int], int],
) -> list[list[int]]:
    if not penalties:
        return matrix
    result = [row[:] for row in matrix]
    for (i, j), penalty in penalties.items():
        if i < len(result) and j < len(result[i]):
            result[i][j] += penalty
    return result


def _merge_result(
    result: TranslatorResult,
    *,
    time_windows: dict[int, tuple[int | None, int | None]] | None = None,
    edge_penalties: dict[tuple[int, int], int] | None = None,
    edge_masks: list[tuple[int, int]] | None = None,
    explanations: list[str] | None = None,
    warnings: list[str] | None = None,
) -> None:
    if time_windows is not None:
        for idx, bounds in result.time_bounds.items():
            existing = time_windows.get(idx, (None, None))
            merged = (
                bounds[0] if bounds[0] is not None else existing[0],
                bounds[1] if bounds[1] is not None else existing[1],
            )
            time_windows[idx] = merged
    if edge_penalties is not None:
        edge_penalties.update(result.edge_penalties)
    if edge_masks is not None:
        edge_masks.extend(result.edge_masks)
    if explanations is not None:
        explanations.append(result.explanation)
    if warnings is not None:
        warnings.extend(result.warnings)
