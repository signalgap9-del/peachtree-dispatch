from app.models import VehicleType
from app.vrp.cost_model import build_risk_adjusted_matrix
from app.vrp.edge_risk import ConstantEdgeRiskProvider
from app.vrp.geometry import FixtureRouteGeometryProvider
from app.vrp.matrix import FixtureMatrixProvider
from app.vrp.ml.artifact import DelayModelArtifact, DelayModelMetrics, DelayModelReleaseGate
from app.vrp.ml.shadow_cost_model import ArtifactShadowCostModel
from app.vrp.models import (
    CostModelConfig,
    DeliveryJob,
    Depot,
    GeoPoint,
    MultiStopRouteRequest,
    RouteGeometry,
    RoutingMatrix,
    VRPScenario,
    Vehicle,
    scenario_to_nodes,
)
from app.vrp.multi_stop import MultiStopRouteService
from app.vrp.optimization_service import VRPOptimizationService
from app.vrp.solvers.ortools_solver import ORToolsVRPSolver


def test_manual_multi_stop_route_preserves_submitted_order() -> None:
    request = _multi_stop_request(mode="MANUAL_ORDER")
    matrix = _matrix(["A", "B", "C", "D"], [
        [0, 600, 200, 900],
        [600, 0, 200, 300],
        [200, 200, 0, 200],
        [900, 300, 200, 0],
    ])
    service = MultiStopRouteService(
        matrix_provider=FixtureMatrixProvider(matrix),
        edge_risk_provider=ConstantEdgeRiskProvider(20),
        geometry_provider=_fixture_geometry(["A", "B", "C", "D"]),
    )

    plan = service.plan(request)

    assert plan.mode == "MANUAL_ORDER"
    assert plan.submitted_sequence == ["A", "B", "C", "D"]
    assert plan.optimized_sequence is None
    assert [leg.from_stop_id for leg in plan.legs] == ["A", "B", "C"]
    assert [leg.to_stop_id for leg in plan.legs] == ["B", "C", "D"]
    assert plan.route_risk_score == 20


def test_optimized_multi_stop_route_reorders_middle_stops_by_adjusted_cost() -> None:
    request = _multi_stop_request(mode="OPTIMIZE_ORDER")
    matrix = _matrix(["A", "B", "C", "D"], [
        [0, 600, 100, 900],
        [600, 0, 100, 100],
        [100, 100, 0, 600],
        [900, 100, 600, 0],
    ])
    service = MultiStopRouteService(
        matrix_provider=FixtureMatrixProvider(matrix),
        edge_risk_provider=ConstantEdgeRiskProvider(0),
        geometry_provider=_fixture_geometry(["A", "B", "C", "D"]),
    )

    plan = service.plan(request)

    assert plan.submitted_sequence == ["A", "B", "C", "D"]
    assert plan.optimized_sequence == ["A", "C", "B", "D"]
    assert plan.sequence_changed is True
    assert "Stop order changed" in plan.explanation[-1]


def test_vrp_solve_respects_vehicle_capacity_and_drops_unservable_jobs() -> None:
    scenario = VRPScenario(
        depot=Depot(name="Atlanta depot", location=GeoPoint(latitude=33.749, longitude=-84.388)),
        vehicles=[
            Vehicle(
                vehicle_id="van-1",
                vehicle_type=VehicleType.VAN,
                capacity_units=1,
                start_location=GeoPoint(latitude=33.749, longitude=-84.388),
            )
        ],
        jobs=[
            DeliveryJob(job_id="job-1", name="Macon", location=GeoPoint(latitude=32.84, longitude=-83.63), demand_units=1),
            DeliveryJob(job_id="job-2", name="Savannah", location=GeoPoint(latitude=32.08, longitude=-81.09), demand_units=1),
        ],
        cost_model=CostModelConfig(),
    )
    nodes = scenario_to_nodes(scenario)
    matrix = _matrix([node.node_id for node in nodes], [
        [0, 100, 300],
        [100, 0, 200],
        [300, 200, 0],
    ])
    service = VRPOptimizationService(
        matrix_provider=FixtureMatrixProvider(matrix),
        edge_risk_provider=ConstantEdgeRiskProvider(0),
        solvers={"ortools": ORToolsVRPSolver(time_limit_seconds=1)},
    )

    solution = service.solve(scenario)

    assert solution.status == "FEASIBLE"
    assert len(solution.routes) == 1
    assert len(solution.routes[0].stops) == 1
    assert len(solution.dropped_jobs) == 1
    assert set(solution.dropped_jobs).issubset({"job-1", "job-2"})


def test_risk_adjusted_matrix_penalizes_missing_edges() -> None:
    nodes = scenario_to_nodes(VRPScenario(
        depot=Depot(name="Atlanta depot", location=GeoPoint(latitude=33.749, longitude=-84.388)),
        vehicles=[
            Vehicle(
                vehicle_id="van-1",
                capacity_units=2,
                start_location=GeoPoint(latitude=33.749, longitude=-84.388),
            )
        ],
        jobs=[DeliveryJob(job_id="job-1", name="Macon", location=GeoPoint(latitude=32.84, longitude=-83.63))],
    ))
    matrix = RoutingMatrix(
        provider="fixture",
        node_ids=[node.node_id for node in nodes],
        duration_seconds=[[0, None], [100, 0]],
        distance_meters=[[0, None], [1000, 0]],
        source_status="PARTIAL",
    )

    adjusted, edge_costs = build_risk_adjusted_matrix(matrix, nodes, CostModelConfig(), ConstantEdgeRiskProvider(0))

    assert adjusted[0][1] >= 86_400
    assert edge_costs[0].primary_hazard == "Missing route matrix edge"


def test_ml_served_cost_changes_solver_matrix_only_when_model_is_promoted() -> None:
    nodes = scenario_to_nodes(VRPScenario(
        depot=Depot(name="Atlanta depot", location=GeoPoint(latitude=33.749, longitude=-84.388)),
        vehicles=[
            Vehicle(
                vehicle_id="van-1",
                capacity_units=2,
                start_location=GeoPoint(latitude=33.749, longitude=-84.388),
            )
        ],
        jobs=[DeliveryJob(job_id="job-1", name="Macon", location=GeoPoint(latitude=32.84, longitude=-83.63))],
    ))
    matrix = _matrix([node.node_id for node in nodes], [
        [0, 600],
        [600, 0],
    ])
    config = CostModelConfig(
        use_ml_served_cost=True,
        ml_delay_weight=0.5,
        ml_max_delay_seconds=1_200,
        ml_min_confidence=0.1,
    )
    promoted_artifact = _constant_delay_artifact(model_version="served-delay-v1", served_to_users=True)

    adjusted, edge_costs = build_risk_adjusted_matrix(
        matrix,
        nodes,
        config,
        ConstantEdgeRiskProvider(0),
        ArtifactShadowCostModel(promoted_artifact, allow_served_cost=True),
    )

    assert adjusted[0][1] == 1_200
    assert edge_costs[0].adjusted_cost_seconds == 1_200
    assert edge_costs[0].ml_delay_seconds == 1_200
    assert any("ml_served_delay_applied" in item for item in edge_costs[0].explanation)


def test_ml_served_cost_request_fails_closed_when_release_gate_is_not_promoted() -> None:
    nodes = scenario_to_nodes(VRPScenario(
        depot=Depot(name="Atlanta depot", location=GeoPoint(latitude=33.749, longitude=-84.388)),
        vehicles=[
            Vehicle(
                vehicle_id="van-1",
                capacity_units=2,
                start_location=GeoPoint(latitude=33.749, longitude=-84.388),
            )
        ],
        jobs=[DeliveryJob(job_id="job-1", name="Macon", location=GeoPoint(latitude=32.84, longitude=-83.63))],
    ))
    matrix = _matrix([node.node_id for node in nodes], [
        [0, 600],
        [600, 0],
    ])
    config = CostModelConfig(use_ml_served_cost=True, ml_delay_weight=0.5)
    blocked_artifact = _constant_delay_artifact(
        model_version="blocked-delay-v1",
        served_to_users=True,
        release_gate_passed=False,
    )

    adjusted, edge_costs = build_risk_adjusted_matrix(
        matrix,
        nodes,
        config,
        ConstantEdgeRiskProvider(0),
        ArtifactShadowCostModel(blocked_artifact, allow_served_cost=True),
    )

    assert adjusted[0][1] == 600
    assert edge_costs[0].adjusted_cost_seconds == 600
    assert edge_costs[0].ml_delay_seconds == 1_200
    assert any("ml_served_cost_blocked" in item for item in edge_costs[0].explanation)


def _multi_stop_request(mode: str) -> MultiStopRouteRequest:
    return MultiStopRouteRequest.model_validate({
        "mode": mode,
        "vehicleType": "VAN",
        "startStopId": "A",
        "endStopId": "D",
        "stops": [
            {"stopId": "A", "kind": "DEPOT", "name": "Atlanta", "latitude": 33.749, "longitude": -84.388},
            {"stopId": "B", "kind": "DELIVERY", "name": "Savannah", "latitude": 32.0809, "longitude": -81.0912},
            {"stopId": "C", "kind": "DELIVERY", "name": "Macon", "latitude": 32.8407, "longitude": -83.6324},
            {"stopId": "D", "kind": "FINAL", "name": "Jacksonville", "latitude": 30.3322, "longitude": -81.6557},
        ],
        "riskModel": {"weatherRiskWeight": 0.35, "trafficRiskWeight": 0.25, "floodRiskWeight": 0.25},
    })


def _matrix(node_ids: list[str], durations: list[list[float | None]]) -> RoutingMatrix:
    distances = [[None if value is None else value * 15 for value in row] for row in durations]
    return RoutingMatrix(
        provider="fixture",
        node_ids=node_ids,
        duration_seconds=durations,
        distance_meters=distances,
        source_status="LIVE",
    )


def _fixture_geometry(node_ids: list[str]) -> FixtureRouteGeometryProvider:
    legs: dict[tuple[str, str], RouteGeometry] = {}
    for origin in node_ids:
        for destination in node_ids:
            if origin == destination:
                continue
            legs[(origin, destination)] = RouteGeometry(
                coordinates=[[-84.0, 33.0], [-83.0, 32.0]],
                distance_miles=10,
                duration_minutes=12,
                source_status="LIVE",
            )
    return FixtureRouteGeometryProvider(legs)


def _constant_delay_artifact(
    *,
    model_version: str,
    served_to_users: bool,
    release_gate_passed: bool = True,
) -> DelayModelArtifact:
    return DelayModelArtifact(
        model_version=model_version,
        feature_names=[
            "base_duration_hours",
            "base_distance_100_miles",
            "weather_risk_ratio",
            "traffic_risk_ratio",
            "flood_risk_ratio",
            "alert_risk_ratio",
            "max_risk_ratio",
            "has_primary_hazard",
            "vehicle_type_car",
            "vehicle_type_van",
            "vehicle_type_truck",
        ],
        coefficients=[0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
        intercept=1_200,
        metrics=DelayModelMetrics(
            example_count=100,
            train_count=80,
            validation_count=20,
            mae_seconds=120,
            rmse_seconds=150,
            p95_abs_error_seconds=240,
            baseline_mae_seconds=300,
            improvement_over_baseline=0.6,
        ),
        release_gate=DelayModelReleaseGate(
            passed=release_gate_passed,
            min_validation_examples=20,
            max_mae_seconds=300,
            min_improvement_over_baseline=0.1,
            reasons=[] if release_gate_passed else ["test release gate failure"],
        ),
        served_to_users=served_to_users,
    )
