from __future__ import annotations

import os
from pathlib import Path
from typing import Protocol

from pydantic import BaseModel, ConfigDict, Field

from .artifact import DelayModelArtifact, load_delay_model_artifact
from .features import EdgeFeatureVector
from .trainer import predict_delay_seconds


class ShadowCostPrediction(BaseModel):
    model_config = ConfigDict(frozen=True)

    model_version: str = Field(min_length=1, max_length=120)
    predicted_delay_seconds: float = Field(ge=0)
    confidence: float = Field(ge=0, le=1)
    served_to_users: bool
    explanation: list[str] = Field(default_factory=list)


class ShadowCostModel(Protocol):
    model_version: str

    def predict(self, feature: EdgeFeatureVector) -> ShadowCostPrediction:
        ...


class DisabledShadowCostModel:
    model_version = "disabled"

    def predict(self, feature: EdgeFeatureVector) -> ShadowCostPrediction:
        return ShadowCostPrediction(
            model_version=self.model_version,
            predicted_delay_seconds=0,
            confidence=0,
            served_to_users=False,
            explanation=[
                f"ML shadow cost is disabled for feature schema {feature.schema_version}; rule-based cost remains authoritative."
            ],
        )


class ArtifactShadowCostModel:
    def __init__(self, artifact: DelayModelArtifact, *, allow_served_cost: bool = False):
        self.artifact = artifact
        self.model_version = artifact.model_version
        self.allow_served_cost = allow_served_cost

    def predict(self, feature: EdgeFeatureVector) -> ShadowCostPrediction:
        delay = predict_delay_seconds(self.artifact, feature)
        confidence = _confidence(delay, self.artifact.metrics.mae_seconds)
        serving_blockers = _serving_blockers(self.artifact, self.allow_served_cost)
        served_to_users = not serving_blockers
        return ShadowCostPrediction(
            model_version=self.model_version,
            predicted_delay_seconds=delay,
            confidence=confidence,
            served_to_users=served_to_users,
            explanation=[
                (
                    "artifact-backed ML delay model eligible for served cost"
                    if served_to_users
                    else "artifact-backed ML delay model evaluated in shadow mode"
                ),
                f"release_gate_passed={self.artifact.release_gate.passed}",
                *[f"ml_serving_blocker={reason}" for reason in serving_blockers],
                (
                    "caller may apply ML delay to route cost"
                    if served_to_users
                    else "rule-based cost remains authoritative"
                ),
            ],
        )


def load_shadow_cost_model_from_env() -> ShadowCostModel:
    artifact_path = os.getenv("VRP_ML_MODEL_ARTIFACT")
    if not artifact_path:
        return DisabledShadowCostModel()
    path = Path(artifact_path)
    if not path.exists():
        return DisabledShadowCostModel()
    workflow_serving_enabled = os.getenv("VRP_ML_WORKFLOW_MODE", "SHADOW_DISABLED") == "SERVING_ENABLED"
    allow_served_cost = workflow_serving_enabled and os.getenv("VRP_ML_ALLOW_SERVED_COST", "").lower() in {
        "1",
        "true",
        "yes",
    }
    return ArtifactShadowCostModel(
        load_delay_model_artifact(path),
        allow_served_cost=allow_served_cost,
    )


def _confidence(predicted_delay_seconds: float, mae_seconds: float) -> float:
    denominator = max(1.0, predicted_delay_seconds + mae_seconds)
    return max(0.05, min(0.95, 1 - (mae_seconds / denominator)))


def _serving_blockers(artifact: DelayModelArtifact, allow_served_cost: bool) -> list[str]:
    blockers: list[str] = []
    if not allow_served_cost:
        blockers.append("runtime served-cost guard is not enabled")
    if not artifact.served_to_users:
        blockers.append("artifact is not promoted for served user costs")
    if not artifact.release_gate.passed:
        blockers.extend(artifact.release_gate.reasons or ["release gate did not pass"])
    return blockers
