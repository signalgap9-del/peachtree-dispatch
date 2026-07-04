from __future__ import annotations

import json
from datetime import UTC, datetime
from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field

from .features import FEATURE_SCHEMA_VERSION


class DelayModelMetrics(BaseModel):
    model_config = ConfigDict(frozen=True)

    example_count: int = Field(ge=0)
    train_count: int = Field(ge=0)
    validation_count: int = Field(ge=0)
    mae_seconds: float = Field(ge=0)
    rmse_seconds: float = Field(ge=0)
    p95_abs_error_seconds: float = Field(ge=0)
    baseline_mae_seconds: float = Field(ge=0)
    improvement_over_baseline: float


class DelayModelReleaseGate(BaseModel):
    model_config = ConfigDict(frozen=True)

    passed: bool
    min_validation_examples: int = Field(ge=0)
    max_mae_seconds: float = Field(ge=0)
    min_improvement_over_baseline: float
    reasons: list[str] = Field(default_factory=list)


class DelayModelArtifact(BaseModel):
    model_config = ConfigDict(frozen=True)

    model_type: str = "sklearn-ridge-delay-regression"
    model_version: str = Field(min_length=1, max_length=120)
    created_at: str = Field(default_factory=lambda: datetime.now(UTC).isoformat())
    feature_schema_version: str = FEATURE_SCHEMA_VERSION
    label: str = "positive_delay_seconds"
    trainer_backend: str = "scikit-learn"
    trainer_version: str | None = None
    baseline_model: str = "dummy-mean-delay-regressor"
    feature_names: list[str]
    coefficients: list[float]
    intercept: float
    metrics: DelayModelMetrics
    release_gate: DelayModelReleaseGate
    served_to_users: bool = False


def save_delay_model_artifact(path: Path, artifact: DelayModelArtifact) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(artifact.model_dump_json(indent=2), encoding="utf-8")


def load_delay_model_artifact(path: Path) -> DelayModelArtifact:
    return DelayModelArtifact.model_validate(json.loads(path.read_text(encoding="utf-8")))
