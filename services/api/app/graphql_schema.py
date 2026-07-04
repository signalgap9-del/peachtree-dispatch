from __future__ import annotations

import os
from dataclasses import asdict
from datetime import datetime
from typing import Any

import strawberry
from strawberry.fastapi import GraphQLRouter
from strawberry.scalars import JSON

from .vrp.ml.workflow import get_ml_workflow_status
from .vrp.models import MultiStopRouteRequest, VRPScenario
from .vrp.multi_stop import multi_stop_route_service
from .vrp.optimization_service import vrp_optimization_service


@strawberry.type
class KeyValue:
    key: str
    value: str


@strawberry.type
class RouteEngineCapabilities:
    supports_graphql: bool
    max_multi_stop_stops: int
    max_vrp_jobs: int
    supported_solvers: list[str]
    ml_shadow_mode: str


@strawberry.type
class RouteLegType:
    from_stop_id: str
    to_stop_id: str
    sequence: int
    distance_miles: float
    duration_minutes: float
    risk_adjusted_duration_minutes: float
    risk_score: int
    primary_hazard: str | None
    geometry: JSON
    explanation: list[str]


@strawberry.type
class MultiStopRouteType:
    route_id: str
    mode: str
    vehicle_type: str
    submitted_sequence: list[str]
    optimized_sequence: list[str] | None
    sequence_changed: bool
    explanation: list[str]
    total_distance_miles: float
    total_duration_minutes: float
    risk_adjusted_duration_minutes: float
    route_risk_score: int
    legs: list[RouteLegType]
    source_status: list[KeyValue]


@strawberry.type
class VrpStopType:
    job_id: str
    sequence: int
    eta: str | None
    arrival_window_status: str
    leg_duration_minutes: float
    leg_distance_miles: float
    leg_risk_score: int
    primary_hazard: str | None


@strawberry.type
class VrpVehicleRouteType:
    vehicle_id: str
    vehicle_type: str
    stops: list[VrpStopType]
    total_duration_minutes: float
    total_distance_miles: float
    risk_adjusted_duration_minutes: float
    risk_exposure_score: int
    geometry: JSON | None


@strawberry.type
class EdgeCostType:
    from_node_id: str
    to_node_id: str
    base_duration_seconds: float
    base_distance_meters: float
    weather_risk_score: int
    traffic_risk_score: int
    flood_risk_score: int
    alert_risk_score: int
    ml_delay_seconds: float | None
    adjusted_cost_seconds: int
    primary_hazard: str | None
    explanation: list[str]


@strawberry.type
class VrpSolutionType:
    solution_id: str
    solver: str
    status: str
    objective_value: float
    solve_time_ms: int
    routes: list[VrpVehicleRouteType]
    dropped_jobs: list[str]
    source_status: list[KeyValue]
    edge_costs: list[EdgeCostType]


@strawberry.type
class TrainingReadinessType:
    label: str
    ready: bool
    detail: str


@strawberry.type
class MLWorkflowStatusType:
    mode: str
    served_to_users: bool
    active_model_version: str
    feature_schema_version: str
    training_readiness: list[TrainingReadinessType]
    next_actions: list[str]


@strawberry.input
class RiskModelWeightsInput:
    weather_risk_weight: float = 0.35
    traffic_risk_weight: float = 0.25
    flood_risk_weight: float = 0.30
    alert_risk_weight: float = 0.50


@strawberry.input
class GeoPointInput:
    latitude: float
    longitude: float


@strawberry.input
class RouteStopInput:
    stop_id: str
    name: str
    latitude: float
    longitude: float
    kind: str = "WAYPOINT"
    address: str | None = None
    city: str | None = None
    state: str | None = None
    sequence: int | None = None
    demand_units: int = 0
    service_duration_minutes: int = 10
    time_window_start: str | None = None
    time_window_end: str | None = None
    required_vehicle_type: str | None = None


@strawberry.input
class MultiStopRouteInput:
    stops: list[RouteStopInput]
    mode: str = "MANUAL_ORDER"
    vehicle_type: str = "CAR"
    start_stop_id: str | None = None
    end_stop_id: str | None = None
    objective: str = "risk_adjusted_time"
    risk_model: RiskModelWeightsInput | None = None


@strawberry.input
class DepotInput:
    name: str
    location: GeoPointInput
    depot_id: str = "depot"


@strawberry.input
class VehicleInput:
    vehicle_id: str
    start_location: GeoPointInput
    vehicle_type: str = "VAN"
    capacity_units: int = 1
    end_location: GeoPointInput | None = None
    shift_start: str | None = None
    shift_end: str | None = None
    max_distance_miles: float | None = None
    max_duration_minutes: float | None = None


@strawberry.input
class DeliveryJobInput:
    job_id: str
    name: str
    location: GeoPointInput
    demand_units: int = 1
    service_duration_minutes: int = 10
    time_window_start: str | None = None
    time_window_end: str | None = None
    priority: int = 1
    required_vehicle_type: str | None = None
    drop_penalty: int = 10_000


@strawberry.input
class CostModelConfigInput:
    duration_weight: float = 1.0
    distance_weight: float = 0.0
    weather_risk_weight: float = 0.35
    traffic_risk_weight: float = 0.25
    flood_risk_weight: float = 0.30
    alert_risk_weight: float = 0.50
    late_penalty_weight: float = 2.0
    unserved_penalty_weight: float = 10.0
    use_ml_shadow_cost: bool = False


@strawberry.input
class VrpScenarioInput:
    depot: DepotInput
    vehicles: list[VehicleInput]
    jobs: list[DeliveryJobInput]
    scenario_id: str | None = None
    objective: str = "risk_adjusted_time"
    solver: str = "ortools"
    cost_model: CostModelConfigInput | None = None


@strawberry.type
class Query:
    @strawberry.field
    def route_engine_capabilities(self) -> RouteEngineCapabilities:
        return RouteEngineCapabilities(
            supports_graphql=True,
            max_multi_stop_stops=25,
            max_vrp_jobs=100,
            supported_solvers=["ortools"],
            ml_shadow_mode=get_ml_workflow_status().mode,
        )

    @strawberry.field
    def ml_workflow_status(self) -> MLWorkflowStatusType:
        return _ml_workflow_to_graphql()


@strawberry.type
class Mutation:
    @strawberry.mutation
    def plan_multi_stop_route(self, input: MultiStopRouteInput) -> MultiStopRouteType:
        request = MultiStopRouteRequest.model_validate(_compact(asdict(input)))
        return _route_plan_to_graphql(multi_stop_route_service.plan(request))

    @strawberry.mutation
    def optimize_multi_stop_route(self, input: MultiStopRouteInput) -> MultiStopRouteType:
        request = MultiStopRouteRequest.model_validate({**_compact(asdict(input)), "mode": "OPTIMIZE_ORDER"})
        return _route_plan_to_graphql(multi_stop_route_service.plan(request))

    @strawberry.mutation
    def solve_vrp(self, input: VrpScenarioInput) -> VrpSolutionType:
        scenario = VRPScenario.model_validate(_compact(asdict(input)))
        return _vrp_solution_to_graphql(vrp_optimization_service.solve(scenario))


def _compact(value: Any) -> Any:
    if isinstance(value, dict):
        return {key: _compact(item) for key, item in value.items() if item is not None}
    if isinstance(value, list):
        return [_compact(item) for item in value]
    return value


def _enum_value(value: Any) -> str:
    return value.value if hasattr(value, "value") else str(value)


def _iso(value: datetime | None) -> str | None:
    return value.isoformat() if value else None


def _source_status(status: dict[str, str]) -> list[KeyValue]:
    return [KeyValue(key=key, value=value) for key, value in status.items()]


def _route_plan_to_graphql(plan) -> MultiStopRouteType:
    return MultiStopRouteType(
        route_id=plan.route_id,
        mode=_enum_value(plan.mode),
        vehicle_type=_enum_value(plan.vehicle_type),
        submitted_sequence=plan.submitted_sequence,
        optimized_sequence=plan.optimized_sequence,
        sequence_changed=plan.sequence_changed,
        explanation=plan.explanation,
        total_distance_miles=plan.total_distance_miles,
        total_duration_minutes=plan.total_duration_minutes,
        risk_adjusted_duration_minutes=plan.risk_adjusted_duration_minutes,
        route_risk_score=plan.route_risk_score,
        legs=[
            RouteLegType(
                from_stop_id=leg.from_stop_id,
                to_stop_id=leg.to_stop_id,
                sequence=leg.sequence,
                distance_miles=leg.distance_miles,
                duration_minutes=leg.duration_minutes,
                risk_adjusted_duration_minutes=leg.risk_adjusted_duration_minutes,
                risk_score=leg.risk_score,
                primary_hazard=leg.primary_hazard,
                geometry=leg.geometry,
                explanation=leg.explanation,
            )
            for leg in plan.legs
        ],
        source_status=_source_status(plan.source_status),
    )


def _vrp_solution_to_graphql(solution) -> VrpSolutionType:
    return VrpSolutionType(
        solution_id=solution.solution_id,
        solver=solution.solver,
        status=solution.status,
        objective_value=solution.objective_value,
        solve_time_ms=solution.solve_time_ms,
        routes=[
            VrpVehicleRouteType(
                vehicle_id=route.vehicle_id,
                vehicle_type=_enum_value(route.vehicle_type),
                stops=[
                    VrpStopType(
                        job_id=stop.job_id,
                        sequence=stop.sequence,
                        eta=_iso(stop.eta),
                        arrival_window_status=stop.arrival_window_status,
                        leg_duration_minutes=stop.leg_duration_minutes,
                        leg_distance_miles=stop.leg_distance_miles,
                        leg_risk_score=stop.leg_risk_score,
                        primary_hazard=stop.primary_hazard,
                    )
                    for stop in route.stops
                ],
                total_duration_minutes=route.total_duration_minutes,
                total_distance_miles=route.total_distance_miles,
                risk_adjusted_duration_minutes=route.risk_adjusted_duration_minutes,
                risk_exposure_score=route.risk_exposure_score,
                geometry=route.geometry,
            )
            for route in solution.routes
        ],
        dropped_jobs=solution.dropped_jobs,
        source_status=_source_status(solution.source_status),
        edge_costs=[
            EdgeCostType(
                from_node_id=edge.from_node_id,
                to_node_id=edge.to_node_id,
                base_duration_seconds=edge.base_duration_seconds,
                base_distance_meters=edge.base_distance_meters,
                weather_risk_score=edge.weather_risk_score,
                traffic_risk_score=edge.traffic_risk_score,
                flood_risk_score=edge.flood_risk_score,
                alert_risk_score=edge.alert_risk_score,
                ml_delay_seconds=edge.ml_delay_seconds,
                adjusted_cost_seconds=edge.adjusted_cost_seconds,
                primary_hazard=edge.primary_hazard,
                explanation=edge.explanation,
            )
            for edge in solution.edge_costs
        ],
    )


def _ml_workflow_to_graphql() -> MLWorkflowStatusType:
    status = get_ml_workflow_status()
    return MLWorkflowStatusType(
        mode=status.mode,
        served_to_users=status.served_to_users,
        active_model_version=status.active_model_version,
        feature_schema_version=status.feature_schema_version,
        training_readiness=[
            TrainingReadinessType(label=item.label, ready=item.ready, detail=item.detail)
            for item in status.training_readiness
        ],
        next_actions=status.next_actions,
    )


schema = strawberry.Schema(query=Query, mutation=Mutation)
graphql_router = GraphQLRouter(
    schema,
    graphql_ide="graphiql" if os.getenv("GRAPHQL_IDE_ENABLED", "false").lower() == "true" else None,
    allow_queries_via_get=False,
    multipart_uploads_enabled=False,
)
