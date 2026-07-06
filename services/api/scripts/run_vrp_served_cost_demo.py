from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = Path(__file__).resolve().parents[3]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.models import VehicleType
from app.vrp.edge_risk import EdgeRiskProvider
from app.vrp.matrix import FixtureMatrixProvider
from app.vrp.ml.artifact import save_delay_model_artifact
from app.vrp.ml.dataset import SavedRouteTrainingExamplePayload, saved_route_examples_to_delay_dataset
from app.vrp.ml.shadow_cost_model import ArtifactShadowCostModel
from app.vrp.ml.trainer import DelayModelTrainingConfig, train_delay_model
from app.vrp.models import (
    CostModelConfig,
    DeliveryJob,
    Depot,
    EdgeRisk,
    GeoNode,
    GeoPoint,
    RoutingMatrix,
    VRPScenario,
    Vehicle,
    scenario_to_nodes,
)
from app.vrp.optimization_service import VRPOptimizationService
from app.vrp.solvers.ortools_solver import ORToolsVRPSolver


class DemoCorridorRiskProvider(EdgeRiskProvider):
    def score_edge(self, origin: GeoNode, destination: GeoNode) -> EdgeRisk:
        if destination.node_id == "job-savannah-port":
            return EdgeRisk(
                weather_risk_score=76,
                traffic_risk_score=48,
                flood_risk_score=72,
                alert_risk_score=82,
                primary_hazard="Flash Flood Warning",
                source_coverage=0.85,
                explanation=["demo live-like risk: coastal flood and road-event exposure"],
            )
        if destination.node_id == "job-macon-crossdock":
            return EdgeRisk(
                weather_risk_score=38,
                traffic_risk_score=22,
                flood_risk_score=10,
                alert_risk_score=20,
                primary_hazard="Thunderstorm",
                source_coverage=0.8,
                explanation=["demo live-like risk: scattered storm exposure"],
            )
        return EdgeRisk(
            weather_risk_score=18,
            traffic_risk_score=12,
            flood_risk_score=8,
            alert_risk_score=0,
            primary_hazard=None,
            source_coverage=0.8,
            explanation=["demo live-like risk: clear corridor"],
        )


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Run a self-contained AtmosPath demo showing guarded ML served-cost routing."
    )
    parser.add_argument(
        "--artifact-dir",
        type=Path,
        default=REPO_ROOT / "tmp" / "demo-vrp-ml",
        help="Directory for generated demo model artifacts. Defaults to repo tmp/.",
    )
    parser.add_argument("--model-version", default="demo-served-delay-v1")
    args = parser.parse_args()

    args.artifact_dir.mkdir(parents=True, exist_ok=True)
    shadow_artifact_path = args.artifact_dir / "shadow-delay-model.json"
    served_artifact_path = args.artifact_dir / "served-delay-model.json"

    training_result = train_delay_model(
        saved_route_examples_to_delay_dataset(_demo_training_examples()),
        DelayModelTrainingConfig(
            model_version=args.model_version,
            max_mae_seconds=1_200,
            min_improvement_over_baseline=-1,
            validation_fraction=0.25,
        ),
    )
    shadow_artifact = training_result.artifact
    served_artifact = shadow_artifact.model_copy(update={"served_to_users": True})
    save_delay_model_artifact(shadow_artifact_path, shadow_artifact)
    save_delay_model_artifact(served_artifact_path, served_artifact)

    scenario = _demo_scenario(use_ml_served_cost=False)
    baseline_solution = _solve_demo(scenario, shadow_cost_model=None)
    served_solution = _solve_demo(
        _demo_scenario(use_ml_served_cost=True),
        shadow_cost_model=ArtifactShadowCostModel(served_artifact, allow_served_cost=True),
    )
    high_risk_edge = next(
        edge
        for edge in served_solution.edge_costs
        if edge.to_node_id == "job-savannah-port"
    )

    print(
        json.dumps(
            {
                "demo": "vrp-ml-served-cost",
                "modelVersion": served_artifact.model_version,
                "artifactPaths": {
                    "shadow": str(shadow_artifact_path),
                    "served": str(served_artifact_path),
                },
                "releaseGatePassed": served_artifact.release_gate.passed,
                "validationMaeSeconds": served_artifact.metrics.mae_seconds,
                "baseline": _solution_summary(baseline_solution),
                "servedMl": _solution_summary(served_solution),
                "savannahEdge": {
                    "baseDurationSeconds": high_risk_edge.base_duration_seconds,
                    "adjustedCostSeconds": high_risk_edge.adjusted_cost_seconds,
                    "mlDelaySeconds": high_risk_edge.ml_delay_seconds,
                    "primaryHazard": high_risk_edge.primary_hazard,
                    "explanation": high_risk_edge.explanation,
                },
            },
            indent=2,
        )
    )
    return 0


def _solve_demo(scenario: VRPScenario, shadow_cost_model: ArtifactShadowCostModel | None) -> object:
    nodes = scenario_to_nodes(scenario)
    matrix = RoutingMatrix(
        provider="fixture-road-network",
        node_ids=[node.node_id for node in nodes],
        duration_seconds=[
            [0, 5_400, 10_800, 16_200],
            [5_400, 0, 6_300, 11_700],
            [10_800, 6_300, 0, 7_200],
            [16_200, 11_700, 7_200, 0],
        ],
        distance_meters=[
            [0, 135_000, 260_000, 400_000],
            [135_000, 0, 145_000, 275_000],
            [260_000, 145_000, 0, 190_000],
            [400_000, 275_000, 190_000, 0],
        ],
        source_status="LIVE",
        provider_request_id="demo-fixture",
    )
    service = VRPOptimizationService(
        matrix_provider=FixtureMatrixProvider(matrix),
        edge_risk_provider=DemoCorridorRiskProvider(),
        solvers={"ortools": ORToolsVRPSolver(time_limit_seconds=1)},
        shadow_cost_model=shadow_cost_model,
    )
    return service.solve(scenario)


def _demo_scenario(*, use_ml_served_cost: bool) -> VRPScenario:
    return VRPScenario(
        scenario_id="demo-atlanta-coastal-corridor",
        depot=Depot(name="Atlanta operations hub", location=GeoPoint(latitude=33.7490, longitude=-84.3880)),
        vehicles=[
            Vehicle(
                vehicle_id="demo-van-1",
                vehicle_type=VehicleType.VAN,
                capacity_units=3,
                start_location=GeoPoint(latitude=33.7490, longitude=-84.3880),
            )
        ],
        jobs=[
            DeliveryJob(
                job_id="job-macon-crossdock",
                name="Macon cross-dock",
                location=GeoPoint(latitude=32.8407, longitude=-83.6324),
                demand_units=1,
                drop_penalty=100_000,
            ),
            DeliveryJob(
                job_id="job-savannah-port",
                name="Savannah port corridor",
                location=GeoPoint(latitude=32.0809, longitude=-81.0998),
                demand_units=1,
                drop_penalty=100_000,
            ),
            DeliveryJob(
                job_id="job-charleston-relief",
                name="Charleston relief waypoint",
                location=GeoPoint(latitude=32.7765, longitude=-79.9311),
                demand_units=1,
                drop_penalty=100_000,
            ),
        ],
        cost_model=CostModelConfig(
            use_ml_served_cost=use_ml_served_cost,
            ml_delay_weight=0.75,
            ml_min_confidence=0.0,
            ml_max_delay_seconds=7_200,
        ),
    )


def _demo_training_examples() -> list[SavedRouteTrainingExamplePayload]:
    rows = []
    for index in range(1, 21):
        flood = index % 3 == 0
        road_event = index % 4 == 0
        risk = 28 + index * 3
        planned_duration = 80 + index * 4
        delay = 4 + index * 1.8 + (18 if flood else 0) + (12 if road_event else 0)
        hazards = []
        if flood:
            hazards.append("Flash Flood Warning")
        if road_event:
            hazards.append("Road closure")
        if not hazards:
            hazards.append("Thunderstorm")
        rows.append(
            SavedRouteTrainingExamplePayload.model_validate(
                {
                    "observationId": f"demo-obs-{index}",
                    "savedItemId": "atlanta-coastal-corridor",
                    "vehicleType": "VAN",
                    "distanceMiles": 95 + index * 9,
                    "plannedDurationMinutes": planned_duration,
                    "climateDelayMinutes": max(0, delay / 2),
                    "plannedRiskScore": min(100, risk),
                    "generatedAt": "2026-07-04T00:00:00Z",
                    "observedAt": f"2026-07-04T{index % 24:02d}:00:00Z",
                    "actualDurationMinutes": planned_duration + delay,
                    "delayLabelMinutes": delay,
                    "observedRiskScore": min(100, risk + 8),
                    "plannedHazards": hazards,
                    "encounteredHazards": hazards,
                    "weatherSummary": "demo corridor storm and flood exposure",
                    "roadEventSummary": "demo road event exposure" if road_event else "none",
                    "source": "DEMO_FIXTURE",
                }
            )
        )
    return rows


def _solution_summary(solution) -> dict:
    return {
        "status": solution.status,
        "objectiveValue": solution.objective_value,
        "routeCount": len(solution.routes),
        "droppedJobs": solution.dropped_jobs,
        "sourceStatus": solution.source_status,
        "riskAdjustedMinutes": [
            route.risk_adjusted_duration_minutes
            for route in solution.routes
        ],
    }


if __name__ == "__main__":
    raise SystemExit(main())
