from __future__ import annotations

from typing import Protocol

from .models import EdgeRisk, GeoNode


class EdgeRiskProvider(Protocol):
    def score_edge(self, origin: GeoNode, destination: GeoNode) -> EdgeRisk:
        ...


class RuleBasedEdgeRiskProvider:
    """Low-cost deterministic edge risk provider.

    This is a real cost signal shape, not a claim of perfect weather accuracy.
    Later providers can replace it with NWS, flood, traffic, WZDx and 511 data
    behind this same interface.
    """

    source_status = "RULE_BASED"

    def score_edge(self, origin: GeoNode, destination: GeoNode) -> EdgeRisk:
        midpoint_lat = (origin.latitude + destination.latitude) / 2
        midpoint_lon = (origin.longitude + destination.longitude) / 2
        southness = max(0.0, 36.5 - midpoint_lat)
        coastalness = max(0.0, -78.0 - midpoint_lon)
        weather = min(100, round(10 + southness * 3.0 + coastalness * 1.5))
        flood = min(100, round(max(0.0, 33.0 - midpoint_lat) * 2.4 + coastalness * 1.8))
        primary = "Flood" if flood >= 45 else "Weather" if weather >= 45 else None
        return EdgeRisk(
            weather_risk_score=weather,
            flood_risk_score=flood,
            primary_hazard=primary,
            source_coverage=0.5,
            explanation=[
                "rule-based edge risk provider",
                f"midpoint weather proxy={weather}",
                f"midpoint flood proxy={flood}",
            ],
        )


class ConstantEdgeRiskProvider:
    def __init__(self, risk_score: int = 0):
        self.risk_score = risk_score
        self.source_status = "CONSTANT_FIXTURE"

    def score_edge(self, origin: GeoNode, destination: GeoNode) -> EdgeRisk:
        return EdgeRisk(
            weather_risk_score=self.risk_score,
            primary_hazard="Fixture" if self.risk_score else None,
            source_coverage=1.0,
            explanation=[f"constant fixture risk {self.risk_score}"],
        )


def build_default_edge_risk_provider() -> EdgeRiskProvider:
    from .road_event_risk import build_default_road_event_edge_risk_provider

    return build_default_road_event_edge_risk_provider(RuleBasedEdgeRiskProvider())
