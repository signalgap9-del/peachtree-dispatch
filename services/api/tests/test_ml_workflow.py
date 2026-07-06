from pathlib import Path
import json
import subprocess
import sys

from app.models import VehicleType
from app.vrp.edge_risk import ConstantEdgeRiskProvider
from app.vrp.matrix import FixtureMatrixProvider
from app.vrp.ml.artifact import load_delay_model_artifact, save_delay_model_artifact
from app.vrp.ml.dataset import SavedRouteTrainingExamplePayload, saved_route_examples_to_delay_dataset
from app.vrp.ml.features import edge_cost_to_feature_vector
from app.vrp.ml.shadow_cost_model import ArtifactShadowCostModel, DisabledShadowCostModel, load_shadow_cost_model_from_env
from app.vrp.ml.trainer import DelayModelTrainingConfig, predict_delay_seconds, train_delay_model
from app.vrp.ml.workflow import get_ml_workflow_status
from app.vrp.models import CostModelConfig, DeliveryJob, Depot, EdgeCost, GeoPoint, RoutingMatrix, VRPScenario, Vehicle, scenario_to_nodes
from app.vrp.optimization_service import VRPOptimizationService
from app.vrp.solvers.ortools_solver import ORToolsVRPSolver


def test_edge_cost_feature_vector_is_stable_and_training_safe() -> None:
    edge = EdgeCost(
        from_node_id="atlanta",
        to_node_id="macon",
        base_duration_seconds=5400,
        base_distance_meters=135_000,
        weather_risk_score=42,
        traffic_risk_score=22,
        flood_risk_score=15,
        alert_risk_score=60,
        adjusted_cost_seconds=7200,
        primary_hazard="Flash Flood Warning",
        explanation=["Rule-based flood alert penalty applied."],
    )

    feature = edge_cost_to_feature_vector(edge, scenario_id="scenario-1", vehicle_type="VAN")

    assert feature.schema_version == "edge-cost-v1"
    assert feature.scenario_id == "scenario-1"
    assert feature.from_node_id == "atlanta"
    assert feature.to_node_id == "macon"
    assert feature.base_duration_seconds == 5400
    assert feature.base_distance_meters == 135_000
    assert feature.max_risk_score == 60
    assert feature.primary_hazard == "Flash Flood Warning"
    assert feature.feature_values["vehicle_type_van"] == 1.0
    assert "explanation" not in feature.feature_values


def test_disabled_shadow_cost_model_never_changes_served_cost() -> None:
    model = DisabledShadowCostModel()
    edge = EdgeCost(
        from_node_id="atlanta",
        to_node_id="macon",
        base_duration_seconds=5400,
        base_distance_meters=135_000,
        weather_risk_score=42,
        traffic_risk_score=22,
        flood_risk_score=15,
        alert_risk_score=60,
        adjusted_cost_seconds=7200,
        primary_hazard="Flash Flood Warning",
    )

    prediction = model.predict(edge_cost_to_feature_vector(edge))

    assert prediction.model_version == "disabled"
    assert prediction.predicted_delay_seconds == 0
    assert prediction.served_to_users is False
    assert prediction.confidence == 0


def test_ml_workflow_status_defaults_to_shadow_disabled() -> None:
    status = get_ml_workflow_status()

    assert status.mode == "SHADOW_DISABLED"
    assert status.served_to_users is False
    assert status.feature_schema_version == "edge-cost-v1"
    assert [item.label for item in status.training_readiness] == [
        "route_observations",
        "baseline_trainer",
        "model_artifact",
        "backtest_gate",
        "served_cost_guard",
    ]
    assert status.training_readiness[0].ready is True
    assert status.training_readiness[1].ready is True


def test_saved_route_observations_become_delay_training_examples() -> None:
    dataset = saved_route_examples_to_delay_dataset([
        _saved_route_example(index=1, planned_duration=80, risk=45, delay=12, hazards=["Flood Watch"]),
    ])

    example = dataset[0]

    assert example.source_id == "obs-1"
    assert example.observed_delay_seconds == 720
    assert example.feature.base_duration_seconds == 4800
    assert example.feature.flood_risk_score == 70
    assert example.feature.vehicle_type == "TRUCK"


def test_delay_model_trains_backtests_and_round_trips_artifact(tmp_path: Path) -> None:
    dataset = saved_route_examples_to_delay_dataset([
        _saved_route_example(index=index, planned_duration=55 + index * 6, risk=25 + index * 5, delay=5 + index * 2)
        for index in range(1, 13)
    ])

    result = train_delay_model(
        dataset,
        DelayModelTrainingConfig(
            model_version="test-delay-v1",
            max_mae_seconds=900,
            min_improvement_over_baseline=-1,
        ),
    )

    assert result.artifact.model_version == "test-delay-v1"
    assert result.artifact.model_type == "sklearn-ridge-delay-regression"
    assert result.artifact.trainer_backend == "scikit-learn"
    assert result.artifact.trainer_version is not None
    assert result.artifact.baseline_model == "dummy-mean-delay-regressor"
    assert result.artifact.metrics.validation_count >= 2
    assert result.artifact.release_gate.passed is True
    assert result.artifact.served_to_users is False
    assert predict_delay_seconds(result.artifact, dataset[-1].feature) > 0

    artifact_path = tmp_path / "delay-model.json"
    save_delay_model_artifact(artifact_path, result.artifact)
    loaded = load_delay_model_artifact(artifact_path)

    assert loaded.model_version == "test-delay-v1"
    assert predict_delay_seconds(loaded, dataset[-1].feature) > 0


def test_training_cli_writes_artifact(tmp_path: Path) -> None:
    input_path = tmp_path / "saved-route-examples.json"
    output_path = tmp_path / "delay-model.json"
    examples = [
        _saved_route_example(index=index, planned_duration=65 + index * 3, risk=20 + index * 4, delay=3 + index * 2)
        for index in range(1, 9)
    ]
    input_path.write_text(
        json.dumps([example.model_dump(mode="json", by_alias=True) for example in examples]),
        encoding="utf-8",
    )

    completed = subprocess.run(
        [
            sys.executable,
            "scripts/train_vrp_delay_model.py",
            "--input",
            str(input_path),
            "--output",
            str(output_path),
            "--model-version",
            "cli-delay-test",
            "--min-improvement-over-baseline",
            "-1",
        ],
        check=True,
        cwd=Path(__file__).resolve().parents[1],
        capture_output=True,
        text=True,
    )
    summary = json.loads(completed.stdout)
    artifact = load_delay_model_artifact(output_path)

    assert summary["modelVersion"] == "cli-delay-test"
    assert summary["releaseGatePassed"] is True
    assert artifact.trainer_backend == "scikit-learn"


def test_promote_cli_marks_gated_artifact_for_served_cost(tmp_path: Path) -> None:
    input_path = tmp_path / "shadow-delay-model.json"
    output_path = tmp_path / "served-delay-model.json"
    examples = [
        _saved_route_example(index=index, planned_duration=65 + index * 3, risk=20 + index * 4, delay=3 + index * 2)
        for index in range(1, 9)
    ]
    artifact = train_delay_model(
        saved_route_examples_to_delay_dataset(examples),
        DelayModelTrainingConfig(model_version="promote-delay-test", min_improvement_over_baseline=-1),
    ).artifact
    save_delay_model_artifact(input_path, artifact)

    completed = subprocess.run(
        [
            sys.executable,
            "scripts/promote_vrp_delay_model.py",
            "--input",
            str(input_path),
            "--output",
            str(output_path),
        ],
        check=True,
        cwd=Path(__file__).resolve().parents[1],
        capture_output=True,
        text=True,
    )
    summary = json.loads(completed.stdout)
    promoted = load_delay_model_artifact(output_path)

    assert summary["promoted"] is True
    assert summary["servedToUsers"] is True
    assert promoted.served_to_users is True
    assert promoted.release_gate.passed is True


def test_ml_workflow_status_reports_serving_enabled_for_promoted_artifact(tmp_path: Path, monkeypatch) -> None:
    artifact_path = tmp_path / "served-delay-model.json"
    examples = [
        _saved_route_example(index=index, planned_duration=65 + index * 3, risk=20 + index * 4, delay=3 + index * 2)
        for index in range(1, 9)
    ]
    artifact = train_delay_model(
        saved_route_examples_to_delay_dataset(examples),
        DelayModelTrainingConfig(model_version="served-status-test", min_improvement_over_baseline=-1),
    ).artifact.model_copy(update={"served_to_users": True})
    save_delay_model_artifact(artifact_path, artifact)
    monkeypatch.setenv("VRP_ML_MODEL_ARTIFACT", str(artifact_path))
    monkeypatch.setenv("VRP_ML_WORKFLOW_MODE", "SERVING_ENABLED")
    monkeypatch.setenv("VRP_ML_ALLOW_SERVED_COST", "true")

    status = get_ml_workflow_status()

    assert status.mode == "SERVING_ENABLED"
    assert status.served_to_users is True
    assert status.active_model_version == "served-status-test"
    assert status.training_readiness[-1].label == "served_cost_guard"
    assert status.training_readiness[-1].ready is True


def test_env_loaded_model_requires_serving_workflow_mode(tmp_path: Path, monkeypatch) -> None:
    artifact_path = tmp_path / "served-delay-model.json"
    examples = [
        _saved_route_example(index=index, planned_duration=65 + index * 3, risk=20 + index * 4, delay=3 + index * 2)
        for index in range(1, 9)
    ]
    artifact = train_delay_model(
        saved_route_examples_to_delay_dataset(examples),
        DelayModelTrainingConfig(model_version="env-guard-test", min_improvement_over_baseline=-1),
    ).artifact.model_copy(update={"served_to_users": True})
    save_delay_model_artifact(artifact_path, artifact)
    monkeypatch.setenv("VRP_ML_MODEL_ARTIFACT", str(artifact_path))
    monkeypatch.setenv("VRP_ML_ALLOW_SERVED_COST", "true")
    monkeypatch.setenv("VRP_ML_WORKFLOW_MODE", "SHADOW_EVALUATING")

    model = load_shadow_cost_model_from_env()
    prediction = model.predict(saved_route_examples_to_delay_dataset(examples)[0].feature)

    assert prediction.model_version == "env-guard-test"
    assert prediction.served_to_users is False
    assert any("runtime served-cost guard" in item for item in prediction.explanation)


def test_artifact_shadow_model_predicts_without_serving_users() -> None:
    dataset = saved_route_examples_to_delay_dataset([
        _saved_route_example(index=index, planned_duration=60 + index * 4, risk=30 + index * 4, delay=8 + index * 1.5)
        for index in range(1, 10)
    ])
    artifact = train_delay_model(
        dataset,
        DelayModelTrainingConfig(model_version="shadow-test", min_improvement_over_baseline=-1),
    ).artifact
    model = ArtifactShadowCostModel(artifact)

    prediction = model.predict(dataset[-1].feature)

    assert prediction.model_version == "shadow-test"
    assert prediction.predicted_delay_seconds > 0
    assert prediction.confidence > 0
    assert prediction.served_to_users is False
    assert "shadow mode" in prediction.explanation[0]


def test_vrp_solution_records_ml_shadow_delay_without_serving_cost() -> None:
    training_dataset = saved_route_examples_to_delay_dataset([
        _saved_route_example(index=index, planned_duration=50 + index * 8, risk=25 + index * 6, delay=4 + index * 2)
        for index in range(1, 12)
    ])
    artifact = train_delay_model(
        training_dataset,
        DelayModelTrainingConfig(model_version="edge-shadow-v1", min_improvement_over_baseline=-1),
    ).artifact
    scenario = VRPScenario(
        depot=Depot(name="Atlanta depot", location=GeoPoint(latitude=33.749, longitude=-84.388)),
        vehicles=[
            Vehicle(
                vehicle_id="van-1",
                vehicle_type=VehicleType.VAN,
                capacity_units=2,
                start_location=GeoPoint(latitude=33.749, longitude=-84.388),
            )
        ],
        jobs=[DeliveryJob(job_id="job-1", name="Macon", location=GeoPoint(latitude=32.84, longitude=-83.63))],
        cost_model=CostModelConfig(use_ml_shadow_cost=True),
    )
    nodes = scenario_to_nodes(scenario)
    matrix = RoutingMatrix(
        provider="fixture",
        node_ids=[node.node_id for node in nodes],
        duration_seconds=[[0, 3600], [3600, 0]],
        distance_meters=[[0, 120_000], [120_000, 0]],
        source_status="LIVE",
    )
    service = VRPOptimizationService(
        matrix_provider=FixtureMatrixProvider(matrix),
        edge_risk_provider=ConstantEdgeRiskProvider(40),
        solvers={"ortools": ORToolsVRPSolver(time_limit_seconds=1)},
        shadow_cost_model=ArtifactShadowCostModel(artifact),
    )

    solution = service.solve(scenario)

    assert solution.source_status["ml_cost_model"] == "SHADOW_ARTIFACT:edge-shadow-v1"
    assert solution.edge_costs[0].ml_delay_seconds is not None
    assert solution.edge_costs[0].ml_delay_seconds > 0
    assert any("rule-based cost remains authoritative" in item for item in solution.edge_costs[0].explanation)


def _saved_route_example(
    *,
    index: int,
    planned_duration: float,
    risk: int,
    delay: float,
    hazards: list[str] | None = None,
) -> SavedRouteTrainingExamplePayload:
    return SavedRouteTrainingExamplePayload.model_validate({
        "observationId": f"obs-{index}",
        "savedItemId": "route-atl-macon",
        "featureSchemaVersion": "saved-route-observation-v1",
        "vehicleType": "TRUCK",
        "distanceMiles": 80 + index * 3,
        "plannedDurationMinutes": planned_duration,
        "climateDelayMinutes": max(0, delay / 2),
        "plannedRiskScore": risk,
        "generatedAt": "2026-07-04T00:00:00Z",
        "observedAt": f"2026-07-04T{index:02d}:00:00Z",
        "actualDurationMinutes": planned_duration + delay,
        "delayLabelMinutes": delay,
        "observedRiskScore": min(100, risk + 5),
        "plannedHazards": hazards or ["Thunderstorm"],
        "encounteredHazards": hazards or ["Heavy rain"],
        "weatherSummary": "rain and thunderstorm risk",
        "roadEventSummary": "no closure",
        "source": "TEST_FIXTURE",
    })
