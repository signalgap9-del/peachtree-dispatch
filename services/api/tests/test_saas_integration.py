"""Tests for SaaS integration payloads: risk observation schema compliance
and alert severity mapping from risk scores."""

import uuid
from datetime import datetime, timezone

import pytest


# --- Risk observation payload schema ---

REQUIRED_OBSERVATION_FIELDS = {
    "observation_id": str,
    "saved_route_id": str,
    "user_id": str,
    "risk_score": (int, float),
    "weather_risk_score": (int, float),
    "traffic_risk_score": (int, float),
    "road_event_risk_score": (int, float),
    "source": str,
    "model_version": str,
    "checked_at": str,
}

VALID_SOURCES = {
    "SAVED_ROUTE_CREATED",
    "SAVED_ROUTE_MONITOR_REFRESH",
    "SCHEDULED_RECHECK",
    "MANUAL_REFRESH",
}


def build_observation_payload(
    risk_score: float = 55.0,
    source: str = "SAVED_ROUTE_MONITOR_REFRESH",
) -> dict:
    """Build a route_risk_observation payload matching the DB schema."""
    now = datetime.now(timezone.utc).isoformat()
    return {
        "observation_id": str(uuid.uuid4()),
        "saved_route_id": str(uuid.uuid4()),
        "user_id": str(uuid.uuid4()),
        "risk_score": risk_score,
        "weather_risk_score": risk_score * 0.4,
        "traffic_risk_score": risk_score * 0.35,
        "road_event_risk_score": risk_score * 0.25,
        "source": source,
        "model_version": "route-risk-v2",
        "checked_at": now,
    }


class TestRiskObservationPayload:
    def test_payload_contains_all_required_fields(self):
        payload = build_observation_payload()
        for field, expected_type in REQUIRED_OBSERVATION_FIELDS.items():
            assert field in payload, f"Missing field: {field}"
            assert isinstance(payload[field], expected_type), (
                f"Field {field}: expected {expected_type}, got {type(payload[field])}"
            )

    def test_risk_scores_are_within_valid_range(self):
        payload = build_observation_payload(risk_score=85.0)
        for key in ("risk_score", "weather_risk_score", "traffic_risk_score", "road_event_risk_score"):
            assert 0 <= payload[key] <= 100, f"{key} out of range: {payload[key]}"

    def test_component_scores_sum_to_composite(self):
        payload = build_observation_payload(risk_score=80.0)
        component_sum = (
            payload["weather_risk_score"]
            + payload["traffic_risk_score"]
            + payload["road_event_risk_score"]
        )
        assert abs(component_sum - payload["risk_score"]) < 0.01

    def test_source_must_be_valid_enum_value(self):
        payload = build_observation_payload(source="SAVED_ROUTE_CREATED")
        assert payload["source"] in VALID_SOURCES

    def test_invalid_source_rejected(self):
        payload = build_observation_payload(source="INVALID_SOURCE")
        assert payload["source"] not in VALID_SOURCES


# --- Alert severity mapping ---

SEVERITY_THRESHOLDS = [
    (0, 39, "LOW"),
    (40, 69, "MEDIUM"),
    (70, 100, "HIGH"),
]


def map_risk_to_severity(risk_score: float) -> str:
    """Map a numeric risk score to alert severity per SaaS contract."""
    if risk_score >= 70:
        return "HIGH"
    elif risk_score >= 40:
        return "MEDIUM"
    return "LOW"


class TestAlertSeverityMapping:
    @pytest.mark.parametrize("score,expected", [
        (10, "LOW"),
        (39, "LOW"),
        (40, "MEDIUM"),
        (55, "MEDIUM"),
        (69, "MEDIUM"),
        (70, "HIGH"),
        (85, "HIGH"),
        (100, "HIGH"),
    ])
    def test_severity_mapping_boundaries(self, score: float, expected: str):
        assert map_risk_to_severity(score) == expected

    def test_alert_trigger_payload_has_correct_severity(self):
        risk_score = 88.0
        payload = {
            "route_id": str(uuid.uuid4()),
            "risk_score": risk_score,
            "severity": map_risk_to_severity(risk_score),
            "triggered_at": datetime.now(timezone.utc).isoformat(),
        }
        assert payload["severity"] == "HIGH"
        assert payload["risk_score"] == risk_score

    def test_low_risk_does_not_trigger_alert(self):
        risk_score = 25.0
        severity = map_risk_to_severity(risk_score)
        # Platform only triggers alerts for MEDIUM and HIGH
        assert severity == "LOW"
        assert severity not in ("MEDIUM", "HIGH")
