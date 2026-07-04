from __future__ import annotations

from fastapi import APIRouter, HTTPException

from .models import MultiStopMode, MultiStopRoutePlan, MultiStopRouteRequest, VRPScenario, VRPSolution
from .multi_stop import multi_stop_route_service
from .optimization_service import vrp_optimization_service

router = APIRouter(tags=["route-engine"])


@router.post("/routes/multi-stop", response_model=MultiStopRoutePlan)
def multi_stop_route(request: MultiStopRouteRequest) -> MultiStopRoutePlan:
    try:
        return multi_stop_route_service.plan(request)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post("/routes/multi-stop/optimize", response_model=MultiStopRoutePlan)
def optimize_multi_stop_route(request: MultiStopRouteRequest) -> MultiStopRoutePlan:
    try:
        return multi_stop_route_service.plan(request.model_copy(update={"mode": MultiStopMode.OPTIMIZE_ORDER}))
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post("/vrp/solve", response_model=VRPSolution)
def solve_vrp(scenario: VRPScenario) -> VRPSolution:
    try:
        return vrp_optimization_service.solve(scenario)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
