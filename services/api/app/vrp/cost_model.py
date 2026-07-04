from __future__ import annotations

from .ml.features import edge_cost_to_feature_vector
from .ml.shadow_cost_model import ShadowCostModel
from .edge_risk import EdgeRiskProvider
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
            row.append(adjusted_cost)
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
            edge_costs.append(_with_shadow_prediction(edge_cost, config, shadow_cost_model))
        adjusted.append(row)

    return adjusted, edge_costs


def edge_cost_lookup(edge_costs: list[EdgeCost]) -> dict[tuple[str, str], EdgeCost]:
    return {(edge.from_node_id, edge.to_node_id): edge for edge in edge_costs}


def _with_shadow_prediction(
    edge: EdgeCost,
    config: CostModelConfig | RiskModelWeights,
    shadow_cost_model: ShadowCostModel | None,
) -> EdgeCost:
    if not getattr(config, "use_ml_shadow_cost", False) or shadow_cost_model is None:
        return edge
    prediction = shadow_cost_model.predict(edge_cost_to_feature_vector(edge))
    return edge.model_copy(update={
        "ml_delay_seconds": prediction.predicted_delay_seconds,
        "explanation": [
            *edge.explanation,
            *prediction.explanation,
            f"ml_shadow_model={prediction.model_version}",
        ],
    })
