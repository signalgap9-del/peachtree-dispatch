from __future__ import annotations

from datetime import datetime
from enum import StrEnum
from typing import Literal
from uuid import uuid4

from pydantic import AliasChoices, BaseModel, ConfigDict, Field, field_validator, model_validator

from ..models import VehicleType


class ApiModel(BaseModel):
    model_config = ConfigDict(populate_by_name=True)


class StopKind(StrEnum):
    DEPOT = "DEPOT"
    PICKUP = "PICKUP"
    DELIVERY = "DELIVERY"
    WAYPOINT = "WAYPOINT"
    REST = "REST"
    FINAL = "FINAL"


class MultiStopMode(StrEnum):
    MANUAL_ORDER = "MANUAL_ORDER"
    OPTIMIZE_ORDER = "OPTIMIZE_ORDER"


Objective = Literal["duration", "risk_adjusted_time"]
SolverName = Literal["ortools", "pyvrp"]
SolutionStatus = Literal["OPTIMAL", "FEASIBLE", "INFEASIBLE", "ERROR"]
ArrivalWindowStatus = Literal["EARLY", "ON_TIME", "LATE", "UNKNOWN"]
MatrixSourceStatus = Literal["LIVE", "PARTIAL", "ESTIMATED", "UNAVAILABLE"]


class GeoPoint(ApiModel):
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)


class GeoNode(ApiModel):
    node_id: str = Field(min_length=1, max_length=120)
    label: str = Field(min_length=1, max_length=200)
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)


class RouteStop(ApiModel):
    stop_id: str = Field(
        min_length=1,
        max_length=120,
        validation_alias=AliasChoices("stop_id", "stopId"),
    )
    kind: StopKind = StopKind.WAYPOINT
    name: str = Field(min_length=1, max_length=200)
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    address: str | None = Field(default=None, max_length=240)
    city: str | None = Field(default=None, max_length=80)
    state: str | None = Field(default=None, max_length=40)
    sequence: int | None = Field(default=None, ge=0)
    demand_units: int = Field(
        default=0,
        ge=0,
        validation_alias=AliasChoices("demand_units", "demandUnits"),
    )
    service_duration_minutes: int = Field(
        default=10,
        ge=0,
        le=24 * 60,
        validation_alias=AliasChoices("service_duration_minutes", "serviceDurationMinutes"),
    )
    time_window_start: datetime | None = Field(
        default=None,
        validation_alias=AliasChoices("time_window_start", "timeWindowStart"),
    )
    time_window_end: datetime | None = Field(
        default=None,
        validation_alias=AliasChoices("time_window_end", "timeWindowEnd"),
    )
    required_vehicle_type: VehicleType | None = Field(
        default=None,
        validation_alias=AliasChoices("required_vehicle_type", "requiredVehicleType"),
    )

    @model_validator(mode="after")
    def validate_time_window(self) -> "RouteStop":
        if self.time_window_start and self.time_window_end and self.time_window_end <= self.time_window_start:
            raise ValueError("time_window_end must be after time_window_start")
        return self


class RiskModelWeights(ApiModel):
    weather_risk_weight: float = Field(
        default=0.35,
        ge=0,
        validation_alias=AliasChoices("weather_risk_weight", "weatherRiskWeight"),
    )
    traffic_risk_weight: float = Field(
        default=0.25,
        ge=0,
        validation_alias=AliasChoices("traffic_risk_weight", "trafficRiskWeight"),
    )
    flood_risk_weight: float = Field(
        default=0.30,
        ge=0,
        validation_alias=AliasChoices("flood_risk_weight", "floodRiskWeight"),
    )
    alert_risk_weight: float = Field(
        default=0.50,
        ge=0,
        validation_alias=AliasChoices("alert_risk_weight", "alertRiskWeight"),
    )
    use_ml_shadow_cost: bool = Field(default=False, validation_alias=AliasChoices("use_ml_shadow_cost", "useMlShadowCost"))
    use_ml_served_cost: bool = Field(default=False, validation_alias=AliasChoices("use_ml_served_cost", "useMlServedCost"))
    ml_delay_weight: float = Field(default=0.35, ge=0, le=2, validation_alias=AliasChoices("ml_delay_weight", "mlDelayWeight"))
    ml_max_delay_seconds: float = Field(default=3600, ge=0, le=24 * 60 * 60, validation_alias=AliasChoices("ml_max_delay_seconds", "mlMaxDelaySeconds"))
    ml_min_confidence: float = Field(default=0.25, ge=0, le=1, validation_alias=AliasChoices("ml_min_confidence", "mlMinConfidence"))


class MultiStopRouteRequest(ApiModel):
    mode: MultiStopMode = MultiStopMode.MANUAL_ORDER
    vehicle_type: VehicleType = Field(
        default=VehicleType.CAR,
        validation_alias=AliasChoices("vehicle_type", "vehicleType"),
    )
    stops: list[RouteStop] = Field(min_length=2, max_length=25)
    start_stop_id: str | None = Field(
        default=None,
        validation_alias=AliasChoices("start_stop_id", "startStopId"),
    )
    end_stop_id: str | None = Field(
        default=None,
        validation_alias=AliasChoices("end_stop_id", "endStopId"),
    )
    objective: Objective = "risk_adjusted_time"
    risk_model: RiskModelWeights = Field(
        default_factory=RiskModelWeights,
        validation_alias=AliasChoices("risk_model", "riskModel"),
    )

    @model_validator(mode="after")
    def validate_stops(self) -> "MultiStopRouteRequest":
        stop_ids = [stop.stop_id for stop in self.stops]
        if len(stop_ids) != len(set(stop_ids)):
            raise ValueError("stop_id values must be unique")
        known = set(stop_ids)
        if self.start_stop_id and self.start_stop_id not in known:
            raise ValueError("start_stop_id must reference a submitted stop")
        if self.end_stop_id and self.end_stop_id not in known:
            raise ValueError("end_stop_id must reference a submitted stop")
        return self


class RouteGeometry(ApiModel):
    coordinates: list[list[float]]
    distance_miles: float = Field(ge=0)
    duration_minutes: float = Field(ge=0)
    source_status: MatrixSourceStatus = "ESTIMATED"


class RouteLeg(ApiModel):
    from_stop_id: str = Field(validation_alias=AliasChoices("from_stop_id", "fromStopId"))
    to_stop_id: str = Field(validation_alias=AliasChoices("to_stop_id", "toStopId"))
    sequence: int = Field(ge=1)
    distance_miles: float = Field(ge=0, validation_alias=AliasChoices("distance_miles", "distanceMiles"))
    duration_minutes: float = Field(ge=0, validation_alias=AliasChoices("duration_minutes", "durationMinutes"))
    risk_adjusted_duration_minutes: float = Field(
        ge=0,
        validation_alias=AliasChoices("risk_adjusted_duration_minutes", "riskAdjustedDurationMinutes"),
    )
    risk_score: int = Field(ge=0, le=100, validation_alias=AliasChoices("risk_score", "riskScore"))
    primary_hazard: str | None = Field(default=None, validation_alias=AliasChoices("primary_hazard", "primaryHazard"))
    geometry: dict
    explanation: list[str] = Field(default_factory=list)


class MultiStopRoutePlan(ApiModel):
    route_id: str = Field(default_factory=lambda: f"route_{uuid4().hex[:12]}", validation_alias=AliasChoices("route_id", "routeId"))
    mode: MultiStopMode
    vehicle_type: VehicleType = Field(validation_alias=AliasChoices("vehicle_type", "vehicleType"))
    submitted_sequence: list[str] = Field(validation_alias=AliasChoices("submitted_sequence", "submittedSequence"))
    optimized_sequence: list[str] | None = Field(default=None, validation_alias=AliasChoices("optimized_sequence", "optimizedSequence"))
    sequence_changed: bool = Field(default=False, validation_alias=AliasChoices("sequence_changed", "sequenceChanged"))
    explanation: list[str] = Field(default_factory=list)
    total_distance_miles: float = Field(ge=0, validation_alias=AliasChoices("total_distance_miles", "totalDistanceMiles"))
    total_duration_minutes: float = Field(ge=0, validation_alias=AliasChoices("total_duration_minutes", "totalDurationMinutes"))
    risk_adjusted_duration_minutes: float = Field(
        ge=0,
        validation_alias=AliasChoices("risk_adjusted_duration_minutes", "riskAdjustedDurationMinutes"),
    )
    route_risk_score: int = Field(ge=0, le=100, validation_alias=AliasChoices("route_risk_score", "routeRiskScore"))
    legs: list[RouteLeg]
    source_status: dict[str, str] = Field(default_factory=dict, validation_alias=AliasChoices("source_status", "sourceStatus"))


class Depot(ApiModel):
    depot_id: str = Field(default="depot", validation_alias=AliasChoices("depot_id", "depotId"))
    name: str = Field(min_length=1, max_length=200)
    location: GeoPoint


class Vehicle(ApiModel):
    vehicle_id: str = Field(min_length=1, max_length=120, validation_alias=AliasChoices("vehicle_id", "vehicleId"))
    vehicle_type: VehicleType = Field(default=VehicleType.VAN, validation_alias=AliasChoices("vehicle_type", "vehicleType"))
    capacity_units: int = Field(default=1, ge=1, validation_alias=AliasChoices("capacity_units", "capacityUnits"))
    start_location: GeoPoint = Field(validation_alias=AliasChoices("start_location", "startLocation"))
    end_location: GeoPoint | None = Field(default=None, validation_alias=AliasChoices("end_location", "endLocation"))
    shift_start: datetime | None = Field(default=None, validation_alias=AliasChoices("shift_start", "shiftStart"))
    shift_end: datetime | None = Field(default=None, validation_alias=AliasChoices("shift_end", "shiftEnd"))
    max_distance_miles: float | None = Field(default=None, gt=0, validation_alias=AliasChoices("max_distance_miles", "maxDistanceMiles"))
    max_duration_minutes: float | None = Field(default=None, gt=0, validation_alias=AliasChoices("max_duration_minutes", "maxDurationMinutes"))

    @model_validator(mode="after")
    def validate_shift(self) -> "Vehicle":
        if self.shift_start and self.shift_end and self.shift_end <= self.shift_start:
            raise ValueError("shift_end must be after shift_start")
        return self


class DeliveryJob(ApiModel):
    job_id: str = Field(min_length=1, max_length=120, validation_alias=AliasChoices("job_id", "jobId"))
    name: str = Field(min_length=1, max_length=200)
    location: GeoPoint
    demand_units: int = Field(default=1, ge=1, validation_alias=AliasChoices("demand_units", "demandUnits"))
    service_duration_minutes: int = Field(default=10, ge=0, validation_alias=AliasChoices("service_duration_minutes", "serviceDurationMinutes"))
    time_window_start: datetime | None = Field(default=None, validation_alias=AliasChoices("time_window_start", "timeWindowStart"))
    time_window_end: datetime | None = Field(default=None, validation_alias=AliasChoices("time_window_end", "timeWindowEnd"))
    priority: int = Field(default=1, ge=1, le=5)
    required_vehicle_type: VehicleType | None = Field(default=None, validation_alias=AliasChoices("required_vehicle_type", "requiredVehicleType"))
    drop_penalty: int = Field(default=10_000, ge=0, validation_alias=AliasChoices("drop_penalty", "dropPenalty"))

    @model_validator(mode="after")
    def validate_time_window(self) -> "DeliveryJob":
        if self.time_window_start and self.time_window_end and self.time_window_end <= self.time_window_start:
            raise ValueError("time_window_end must be after time_window_start")
        return self


class CostModelConfig(ApiModel):
    duration_weight: float = Field(default=1.0, ge=0, validation_alias=AliasChoices("duration_weight", "durationWeight"))
    distance_weight: float = Field(default=0.0, ge=0, validation_alias=AliasChoices("distance_weight", "distanceWeight"))
    weather_risk_weight: float = Field(default=0.35, ge=0, validation_alias=AliasChoices("weather_risk_weight", "weatherRiskWeight"))
    traffic_risk_weight: float = Field(default=0.25, ge=0, validation_alias=AliasChoices("traffic_risk_weight", "trafficRiskWeight"))
    flood_risk_weight: float = Field(default=0.30, ge=0, validation_alias=AliasChoices("flood_risk_weight", "floodRiskWeight"))
    alert_risk_weight: float = Field(default=0.50, ge=0, validation_alias=AliasChoices("alert_risk_weight", "alertRiskWeight"))
    late_penalty_weight: float = Field(default=2.0, ge=0, validation_alias=AliasChoices("late_penalty_weight", "latePenaltyWeight"))
    unserved_penalty_weight: float = Field(default=10.0, ge=0, validation_alias=AliasChoices("unserved_penalty_weight", "unservedPenaltyWeight"))
    use_ml_shadow_cost: bool = Field(default=False, validation_alias=AliasChoices("use_ml_shadow_cost", "useMlShadowCost"))
    use_ml_served_cost: bool = Field(default=False, validation_alias=AliasChoices("use_ml_served_cost", "useMlServedCost"))
    ml_delay_weight: float = Field(default=0.35, ge=0, le=2, validation_alias=AliasChoices("ml_delay_weight", "mlDelayWeight"))
    ml_max_delay_seconds: float = Field(default=3600, ge=0, le=24 * 60 * 60, validation_alias=AliasChoices("ml_max_delay_seconds", "mlMaxDelaySeconds"))
    ml_min_confidence: float = Field(default=0.25, ge=0, le=1, validation_alias=AliasChoices("ml_min_confidence", "mlMinConfidence"))


class VRPScenario(ApiModel):
    scenario_id: str = Field(default_factory=lambda: f"vrp_{uuid4().hex[:12]}", validation_alias=AliasChoices("scenario_id", "scenarioId"))
    depot: Depot
    vehicles: list[Vehicle] = Field(min_length=1, max_length=25)
    jobs: list[DeliveryJob] = Field(min_length=1, max_length=100)
    objective: Objective = "risk_adjusted_time"
    solver: SolverName = "ortools"
    cost_model: CostModelConfig = Field(default_factory=CostModelConfig, validation_alias=AliasChoices("cost_model", "costModel"))

    @field_validator("jobs")
    @classmethod
    def validate_job_ids(cls, jobs: list[DeliveryJob]) -> list[DeliveryJob]:
        job_ids = [job.job_id for job in jobs]
        if len(job_ids) != len(set(job_ids)):
            raise ValueError("job_id values must be unique")
        return jobs


class RoutingMatrix(ApiModel):
    provider: str
    node_ids: list[str] = Field(validation_alias=AliasChoices("node_ids", "nodeIds"))
    duration_seconds: list[list[float | None]] = Field(validation_alias=AliasChoices("duration_seconds", "durationSeconds"))
    distance_meters: list[list[float | None]] = Field(validation_alias=AliasChoices("distance_meters", "distanceMeters"))
    source_status: MatrixSourceStatus = Field(validation_alias=AliasChoices("source_status", "sourceStatus"))
    provider_request_id: str | None = Field(default=None, validation_alias=AliasChoices("provider_request_id", "providerRequestId"))

    @model_validator(mode="after")
    def validate_dimensions(self) -> "RoutingMatrix":
        size = len(self.node_ids)
        if len(self.duration_seconds) != size or len(self.distance_meters) != size:
            raise ValueError("matrix row count must match node count")
        for row in self.duration_seconds:
            if len(row) != size:
                raise ValueError("duration matrix must be square")
        for row in self.distance_meters:
            if len(row) != size:
                raise ValueError("distance matrix must be square")
        return self


class EdgeRisk(ApiModel):
    weather_risk_score: int = Field(default=0, ge=0, le=100, validation_alias=AliasChoices("weather_risk_score", "weatherRiskScore"))
    traffic_risk_score: int = Field(default=0, ge=0, le=100, validation_alias=AliasChoices("traffic_risk_score", "trafficRiskScore"))
    flood_risk_score: int = Field(default=0, ge=0, le=100, validation_alias=AliasChoices("flood_risk_score", "floodRiskScore"))
    alert_risk_score: int = Field(default=0, ge=0, le=100, validation_alias=AliasChoices("alert_risk_score", "alertRiskScore"))
    primary_hazard: str | None = Field(default=None, validation_alias=AliasChoices("primary_hazard", "primaryHazard"))
    source_coverage: float = Field(default=1.0, ge=0, le=1, validation_alias=AliasChoices("source_coverage", "sourceCoverage"))
    explanation: list[str] = Field(default_factory=list)


class EdgeCost(ApiModel):
    from_node_id: str = Field(validation_alias=AliasChoices("from_node_id", "fromNodeId"))
    to_node_id: str = Field(validation_alias=AliasChoices("to_node_id", "toNodeId"))
    base_duration_seconds: float = Field(validation_alias=AliasChoices("base_duration_seconds", "baseDurationSeconds"))
    base_distance_meters: float = Field(validation_alias=AliasChoices("base_distance_meters", "baseDistanceMeters"))
    weather_risk_score: int = Field(validation_alias=AliasChoices("weather_risk_score", "weatherRiskScore"))
    traffic_risk_score: int = Field(validation_alias=AliasChoices("traffic_risk_score", "trafficRiskScore"))
    flood_risk_score: int = Field(validation_alias=AliasChoices("flood_risk_score", "floodRiskScore"))
    alert_risk_score: int = Field(validation_alias=AliasChoices("alert_risk_score", "alertRiskScore"))
    ml_delay_seconds: float | None = Field(default=None, validation_alias=AliasChoices("ml_delay_seconds", "mlDelaySeconds"))
    adjusted_cost_seconds: int = Field(validation_alias=AliasChoices("adjusted_cost_seconds", "adjustedCostSeconds"))
    primary_hazard: str | None = Field(default=None, validation_alias=AliasChoices("primary_hazard", "primaryHazard"))
    explanation: list[str] = Field(default_factory=list)


class VRPStop(ApiModel):
    job_id: str = Field(validation_alias=AliasChoices("job_id", "jobId"))
    sequence: int = Field(ge=1)
    eta: datetime | None = None
    arrival_window_status: ArrivalWindowStatus = Field(default="UNKNOWN", validation_alias=AliasChoices("arrival_window_status", "arrivalWindowStatus"))
    leg_duration_minutes: float = Field(default=0, validation_alias=AliasChoices("leg_duration_minutes", "legDurationMinutes"))
    leg_distance_miles: float = Field(default=0, validation_alias=AliasChoices("leg_distance_miles", "legDistanceMiles"))
    leg_risk_score: int = Field(default=0, ge=0, le=100, validation_alias=AliasChoices("leg_risk_score", "legRiskScore"))
    primary_hazard: str | None = Field(default=None, validation_alias=AliasChoices("primary_hazard", "primaryHazard"))


class VRPVehicleRoute(ApiModel):
    vehicle_id: str = Field(validation_alias=AliasChoices("vehicle_id", "vehicleId"))
    vehicle_type: VehicleType = Field(validation_alias=AliasChoices("vehicle_type", "vehicleType"))
    stops: list[VRPStop]
    total_duration_minutes: float = Field(default=0, validation_alias=AliasChoices("total_duration_minutes", "totalDurationMinutes"))
    total_distance_miles: float = Field(default=0, validation_alias=AliasChoices("total_distance_miles", "totalDistanceMiles"))
    risk_adjusted_duration_minutes: float = Field(default=0, validation_alias=AliasChoices("risk_adjusted_duration_minutes", "riskAdjustedDurationMinutes"))
    risk_exposure_score: int = Field(default=0, ge=0, le=100, validation_alias=AliasChoices("risk_exposure_score", "riskExposureScore"))
    geometry: dict | None = None


class VRPSolution(ApiModel):
    solution_id: str = Field(default_factory=lambda: f"sol_{uuid4().hex[:12]}", validation_alias=AliasChoices("solution_id", "solutionId"))
    solver: str
    status: SolutionStatus
    objective_value: float = Field(validation_alias=AliasChoices("objective_value", "objectiveValue"))
    solve_time_ms: int = Field(validation_alias=AliasChoices("solve_time_ms", "solveTimeMs"))
    routes: list[VRPVehicleRoute]
    dropped_jobs: list[str] = Field(default_factory=list, validation_alias=AliasChoices("dropped_jobs", "droppedJobs"))
    source_status: dict[str, str] = Field(default_factory=dict, validation_alias=AliasChoices("source_status", "sourceStatus"))
    edge_costs: list[EdgeCost] = Field(default_factory=list, validation_alias=AliasChoices("edge_costs", "edgeCosts"))


def stop_to_node(stop: RouteStop) -> GeoNode:
    return GeoNode(node_id=stop.stop_id, label=stop.name, latitude=stop.latitude, longitude=stop.longitude)


def scenario_to_nodes(scenario: VRPScenario) -> list[GeoNode]:
    nodes = [
        GeoNode(
            node_id=scenario.depot.depot_id,
            label=scenario.depot.name,
            latitude=scenario.depot.location.latitude,
            longitude=scenario.depot.location.longitude,
        )
    ]
    nodes.extend(
        GeoNode(
            node_id=job.job_id,
            label=job.name,
            latitude=job.location.latitude,
            longitude=job.location.longitude,
        )
        for job in scenario.jobs
    )
    return nodes
