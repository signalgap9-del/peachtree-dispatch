from app.models import VehicleType
from app.vrp.cost_model import build_risk_adjusted_matrix
from app.vrp.edge_risk import ConstantEdgeRiskProvider
from app.vrp.geometry import FixtureRouteGeometryProvider
from app.vrp.matrix import FixtureMatrixProvider
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
