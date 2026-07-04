from app.vrp.ml.features import edge_cost_to_feature_vector
from app.vrp.ml.shadow_cost_model import DisabledShadowCostModel
from app.vrp.ml.workflow import get_ml_workflow_status
from app.vrp.models import EdgeCost


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
        "weather_join",
        "road_event_join",
        "backtest_gate",
    ]
