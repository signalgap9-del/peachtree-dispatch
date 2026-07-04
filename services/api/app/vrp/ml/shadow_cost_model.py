from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field

from .features import EdgeFeatureVector


class ShadowCostPrediction(BaseModel):
    model_config = ConfigDict(frozen=True)

    model_version: str = Field(min_length=1, max_length=120)
    predicted_delay_seconds: float = Field(ge=0)
    confidence: float = Field(ge=0, le=1)
    served_to_users: bool
    explanation: list[str] = Field(default_factory=list)


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
