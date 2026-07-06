from __future__ import annotations

from .edge_risk import EdgeRiskProvider
from .ml.features import edge_cost_to_feature_vector
from .ml.shadow_cost_model import ShadowCostModel
from .models import CostModelConfig, EdgeCost, GeoNode, RiskModelWeights, RoutingMatrix

MISSING_EDGE_COST_SECONDS = 24 * 60 * 60


def build_risk_adjusted_matrix(
    matrix: RoutingMatrix,
    nodes: list[GeoNode],
    config: CostModelConfig | RiskModelWeights,
    edge_risk_provider: EdgeRiskProvider,
    shadow_cost_model: ShadowCostModel | None = None,
) -> tuple[list[list[int]], list[EdgeCost]]:
    if matrix.node_ids != [node.node_id for node in nodes]:
        raise ValueError("matrix node order must match GeoNode order")

    adjusted: list[list[int]] = []
    edge_costs: list[EdgeCost] = []

    for i, from_node in enumerate(nodes):
        row: list[int] = []
        for j, to_node in enumerate(nodes):
            if i == j:
                row.append(0)
                continue

            base_duration = matrix.duration_seconds[i][j]
            base_distance = matrix.distance_meters[i][j]
            if base_duration is None or base_distance is None:
                row.append(MISSING_EDGE_COST_SECONDS)
                edge_costs.append(
                    EdgeCost(
                        from_node_id=from_node.node_id,
                        to_node_id=to_node.node_id,
                        base_duration_seconds=MISSING_EDGE_COST_SECONDS,
                        base_distance_meters=0,
                        weather_risk_score=100,
                        traffic_risk_score=100,
                        flood_risk_score=100,
                        alert_risk_score=100,
                        adjusted_cost_seconds=MISSING_EDGE_COST_SECONDS,
                        primary_hazard="Missing route matrix edge",
                        explanation=["matrix edge was unavailable"],
                    )
                )
                continue

            risk = edge_risk_provider.score_edge(from_node, to_node)
            distance_weight = getattr(config, "distance_weight", 0.0)
            penalty_seconds = (
                risk.weather_risk_score * config.weather_risk_weight * 6
                + risk.traffic_risk_score * config.traffic_risk_weight * 6
                + risk.flood_risk_score * config.flood_risk_weight * 8
                + risk.alert_risk_score * config.alert_risk_weight * 10
                + (float(base_distance) / 1000) * distance_weight
            )
            duration_weight = getattr(config, "duration_weight", 1.0)
            adjusted_cost = max(0, round(float(base_duration) * duration_weight + penalty_seconds))
            edge_cost = EdgeCost(
                from_node_id=from_node.node_id,
                to_node_id=to_node.node_id,
                base_duration_seconds=float(base_duration),
                base_distance_meters=float(base_distance),
                weather_risk_score=risk.weather_risk_score,
                traffic_risk_score=risk.traffic_risk_score,
                flood_risk_score=risk.flood_risk_score,
                alert_risk_score=risk.alert_risk_score,
                adjusted_cost_seconds=adjusted_cost,
                primary_hazard=risk.primary_hazard,
                explanation=risk.explanation,
            )
            edge_cost = _with_shadow_prediction(edge_cost, config, shadow_cost_model)
            row.append(edge_cost.adjusted_cost_seconds)
            edge_costs.append(edge_cost)
        adjusted.append(row)

    return adjusted, edge_costs


def edge_cost_lookup(edge_costs: list[EdgeCost]) -> dict[tuple[str, str], EdgeCost]:
    return {(edge.from_node_id, edge.to_node_id): edge for edge in edge_costs}


def _with_shadow_prediction(
    edge: EdgeCost,
    config: CostModelConfig | RiskModelWeights,
    shadow_cost_model: ShadowCostModel | None,
) -> EdgeCost:
    use_shadow = getattr(config, "use_ml_shadow_cost", False)
    use_served = getattr(config, "use_ml_served_cost", False)
    if not (use_shadow or use_served) or shadow_cost_model is None:
        return edge
    prediction = shadow_cost_model.predict(edge_cost_to_feature_vector(edge))
    adjusted_cost, served_explanation = _served_cost_adjustment(edge, config, prediction) if use_served else (
        edge.adjusted_cost_seconds,
        [],
    )
    return edge.model_copy(
        update={
            "ml_delay_seconds": prediction.predicted_delay_seconds,
            "adjusted_cost_seconds": adjusted_cost,
            "explanation": [
                *edge.explanation,
                *prediction.explanation,
                *served_explanation,
                f"ml_shadow_model={prediction.model_version}",
            ],
        }
    )


def _served_cost_adjustment(
    edge: EdgeCost,
    config: CostModelConfig | RiskModelWeights,
    prediction,
) -> tuple[int, list[str]]:
    min_confidence = getattr(config, "ml_min_confidence", 0.25)
    if not prediction.served_to_users:
        return edge.adjusted_cost_seconds, ["ml_served_cost_blocked=model_not_promoted_or_gate_failed"]
    if prediction.confidence < min_confidence:
        return edge.adjusted_cost_seconds, [
            f"ml_served_cost_blocked=confidence_below_threshold:{prediction.confidence:.2f}<{min_confidence:.2f}"
        ]

    capped_delay = min(prediction.predicted_delay_seconds, getattr(config, "ml_max_delay_seconds", 3600))
    weight = getattr(config, "ml_delay_weight", 0.35)
    applied_delay = capped_delay * weight
    adjusted_cost = max(0, round(edge.adjusted_cost_seconds + applied_delay))
    return adjusted_cost, [
        f"ml_served_delay_applied={applied_delay:.1f}s",
        f"ml_served_delay_raw={prediction.predicted_delay_seconds:.1f}s",
        f"ml_served_delay_weight={weight:.2f}",
    ]
