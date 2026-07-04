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
    def __init__(self, artifact: DelayModelArtifact):
        self.artifact = artifact
        self.model_version = artifact.model_version

    def predict(self, feature: EdgeFeatureVector) -> ShadowCostPrediction:
        delay = predict_delay_seconds(self.artifact, feature)
        confidence = _confidence(delay, self.artifact.metrics.mae_seconds)
        return ShadowCostPrediction(
            model_version=self.model_version,
            predicted_delay_seconds=delay,
            confidence=confidence,
            served_to_users=False,
            explanation=[
                "artifact-backed ML delay model evaluated in shadow mode",
                f"release_gate_passed={self.artifact.release_gate.passed}",
                "rule-based cost remains authoritative",
            ],
        )


def load_shadow_cost_model_from_env() -> ShadowCostModel:
    artifact_path = os.getenv("VRP_ML_MODEL_ARTIFACT")
    if not artifact_path:
        return DisabledShadowCostModel()
    path = Path(artifact_path)
    if not path.exists():
        return DisabledShadowCostModel()
    return ArtifactShadowCostModel(load_delay_model_artifact(path))


def _confidence(predicted_delay_seconds: float, mae_seconds: float) -> float:
    denominator = max(1.0, predicted_delay_seconds + mae_seconds)
    return max(0.05, min(0.95, 1 - (mae_seconds / denominator)))
