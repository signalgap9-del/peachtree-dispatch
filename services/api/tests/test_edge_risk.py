"""Tests for the rule-based edge risk provider and default provider factory."""
from __future__ import annotations

from app.vrp.edge_risk import (
    ConstantEdgeRiskProvider,
    RuleBasedEdgeRiskProvider,
    build_default_edge_risk_provider,
)
from app.vrp.models import GeoNode


def _edge(lat: float, lon: float) -> tuple[GeoNode, GeoNode]:
    origin = GeoNode(node_id="o", label="O", latitude=lat, longitude=lon)
    destination = GeoNode(node_id="d", label="D", latitude=lat + 0.5, longitude=lon - 0.5)
    return origin, destination


def test_rule_based_southern_coastal_edge_is_hazardous() -> None:
    provider = RuleBasedEdgeRiskProvider()
    origin, destination = _edge(25.0, -80.0)  # near Miami: south + coastal

    risk = provider.score_edge(origin, destination)

    assert risk.weather_risk_score > 0
    assert risk.flood_risk_score > 0
    assert risk.primary_hazard in ("Flood", "Weather")
    assert risk.source_coverage == 0.5
    assert risk.explanation


def test_rule_based_northern_interior_edge_is_calm() -> None:
    provider = RuleBasedEdgeRiskProvider()
    origin, destination = _edge(46.0, -100.0)  # northern interior

    risk = provider.score_edge(origin, destination)

    assert risk.primary_hazard is None


def test_build_default_edge_risk_provider_is_rule_based_without_env(monkeypatch) -> None:
    monkeypatch.delenv("VRP_ROAD_EVENT_FEED_URLS", raising=False)
    provider = build_default_edge_risk_provider()
    assert isinstance(provider, RuleBasedEdgeRiskProvider)


def test_constant_fixture_provider_reports_primary_hazard() -> None:
    origin, destination = _edge(33.0, -84.0)
    risky = ConstantEdgeRiskProvider(80).score_edge(origin, destination)
    calm = ConstantEdgeRiskProvider(0).score_edge(origin, destination)

    assert risky.primary_hazard == "Fixture"
    assert calm.primary_hazard is None
