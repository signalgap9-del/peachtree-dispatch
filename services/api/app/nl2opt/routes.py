from __future__ import annotations

import logging
import time
from typing import Any

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from ..vrp.edge_risk import build_default_edge_risk_provider
from ..vrp.matrix import build_default_matrix_provider
from .formulation import FormulationError, VrpFormulation, formulate_vrp

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/nl2opt", tags=["nl2opt"])


class FormulateRequest(BaseModel):
    """Accepts the VrpConstraints JSON produced by the LLM extraction step."""

    constraints: dict[str, Any] = Field(description="VrpConstraints JSON from Phase 1 LLM extraction")


class FormulateResponse(BaseModel):
    formulation: dict[str, Any]


class SolveResponse(BaseModel):
    formulation: dict[str, Any]
    solver_status: str
    solve_time_ms: int
    routes: list[dict[str, Any]] = Field(default_factory=list)
    explanations: list[str] = Field(default_factory=list)


class _DefaultRiskDataProvider:
    """Fetches national risk data from the local risk module."""

    def get_national_risk(self) -> dict[str, Any]:
        from ..risk import national_risk

        try:
            overview = national_risk()
            return {"storm_eta_minutes": getattr(overview, "storm_eta_minutes", None)}
        except Exception:
            logger.warning("national risk lookup failed; weather deadline will be skipped", exc_info=True)
            return {}


def _build_formulation(constraints: dict[str, Any]) -> VrpFormulation:
    return formulate_vrp(
        constraints=constraints,
        matrix_provider=build_default_matrix_provider(),
        edge_risk_provider=build_default_edge_risk_provider(),
        risk_data_provider=_DefaultRiskDataProvider(),
    )


@router.post("/formulate", response_model=FormulateResponse)
def formulate(request: FormulateRequest) -> FormulateResponse:
    """Translate VrpConstraints JSON into a VrpFormulation without solving."""
    try:
        formulation = _build_formulation(request.constraints)
    except FormulationError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    return FormulateResponse(formulation=formulation.to_dict())


@router.post("/solve", response_model=SolveResponse)
def solve(request: FormulateRequest) -> SolveResponse:
    """Formulate and solve: VrpConstraints JSON -> formulation -> OR-Tools solution."""
    started = time.perf_counter()
    try:
        formulation = _build_formulation(request.constraints)
    except FormulationError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc

    # Solve using the existing OR-Tools solver via a lightweight adapter.
    try:
        routes, solver_status = _solve_formulation(formulation)
    except Exception as exc:
        logger.exception("OR-Tools solve failed")
        routes, solver_status = [], "ERROR"
        formulation.warnings.append(f"Solver error: {exc}")

    elapsed_ms = round((time.perf_counter() - started) * 1000)
    return SolveResponse(
        formulation=formulation.to_dict(),
        solver_status=solver_status,
        solve_time_ms=elapsed_ms,
        routes=routes,
        explanations=formulation.explanations,
    )


def _solve_formulation(formulation: VrpFormulation) -> tuple[list[dict[str, Any]], str]:
    """Run OR-Tools on the formulated instance.

    This is a thin adapter that maps VrpFormulation into the existing
    ORToolsVRPSolver contract.  For Phase 2B we run a single-vehicle TSP-style
    solve; full multi-vehicle VRPTW arrives with the solver upgrade.
    """
    from ortools.constraint_solver import pywrapcp, routing_enums_pb2
    from datetime import timedelta

    node_count = len(formulation.nodes)
    if node_count < 2:
        return [], "INFEASIBLE"

    manager = pywrapcp.RoutingIndexManager(node_count, 1, 0)
    routing = pywrapcp.RoutingModel(manager)

    matrix = formulation.cost_matrix

    def transit_callback(from_index: int, to_index: int) -> int:
        from_node = manager.IndexToNode(from_index)
        to_node = manager.IndexToNode(to_index)
        return int(matrix[from_node][to_node])

    transit_idx = routing.RegisterTransitCallback(transit_callback)
    routing.SetArcCostEvaluatorOfVehicle(transit_idx, 0)

    # Time dimension with soft bounds from time_windows.
    def time_callback(from_index: int) -> int:
        from_node = manager.IndexToNode(from_index)
        to_node = manager.IndexToNode(routing.NextVar(from_index)) if not routing.IsEnd(from_index) else from_node
        return int(matrix[from_node][to_node]) if from_node != to_node else 0

    # Use the transit callback for the time dimension as well.
    routing.AddDimension(transit_idx, formulation.max_time_seconds, formulation.max_time_seconds, True, "Time")
    time_dim = routing.GetDimensionOrDie("Time")

    for node_idx, (earliest, latest) in formulation.time_windows.items():
        index = manager.NodeToIndex(node_idx)
        if earliest is not None:
            time_dim.CumulVar(index).SetMin(earliest)
        if latest is not None:
            time_dim.CumulVar(index).SetMax(latest)

    # Mask infeasible edges.
    for from_node, to_node in formulation.edge_masks:
        from_idx = manager.NodeToIndex(from_node)
        to_idx = manager.NodeToIndex(to_node)
        routing.NextVar(from_idx).RemoveValue(to_idx)

    params = pywrapcp.DefaultRoutingSearchParameters()
    params.first_solution_strategy = routing_enums_pb2.FirstSolutionStrategy.PATH_CHEAPEST_ARC
    params.local_search_metaheuristic = routing_enums_pb2.LocalSearchMetaheuristic.GUIDED_LOCAL_SEARCH
    params.time_limit.FromTimedelta(timedelta(seconds=5))

    solution = routing.SolveWithParameters(params)
    if not solution:
        return [], "INFEASIBLE"

    # Extract route.
    route_stops: list[dict[str, Any]] = []
    index = routing.Start(0)
    sequence = 1
    while not routing.IsEnd(index):
        node = manager.IndexToNode(index)
        route_stops.append({
            "node_id": formulation.nodes[node].node_id,
            "label": formulation.nodes[node].label,
            "sequence": sequence,
        })
        index = solution.Value(routing.NextVar(index))
        sequence += 1
    # Include the return-to-depot node.
    node = manager.IndexToNode(index)
    route_stops.append({
        "node_id": formulation.nodes[node].node_id,
        "label": formulation.nodes[node].label,
        "sequence": sequence,
    })

    return [{"vehicle_id": "vehicle-0", "stops": route_stops}], "FEASIBLE"
