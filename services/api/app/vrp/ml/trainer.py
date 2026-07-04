from __future__ import annotations

from datetime import UTC, datetime

import numpy as np
from pydantic import AliasChoices, BaseModel, ConfigDict, Field

from .artifact import DelayModelArtifact, DelayModelMetrics, DelayModelReleaseGate
from .dataset import DelayTrainingExample
from .features import DELAY_MODEL_FEATURE_NAMES, EdgeFeatureVector, vectorize_delay_features


class DelayModelTrainingConfig(BaseModel):
    model_config = ConfigDict(frozen=True, populate_by_name=True)

    model_version: str | None = Field(
        default=None,
        max_length=120,
        validation_alias=AliasChoices("model_version", "modelVersion"),
    )
    validation_fraction: float = Field(
        default=0.25,
        ge=0.05,
        le=0.5,
        validation_alias=AliasChoices("validation_fraction", "validationFraction"),
    )
    regularization_alpha: float = Field(
        default=0.1,
        ge=0,
        le=1000,
        validation_alias=AliasChoices("regularization_alpha", "regularizationAlpha"),
    )
    min_validation_examples: int = Field(
        default=2,
        ge=1,
        le=10_000,
        validation_alias=AliasChoices("min_validation_examples", "minValidationExamples"),
    )
    max_mae_seconds: float = Field(
        default=900,
        ge=1,
        validation_alias=AliasChoices("max_mae_seconds", "maxMaeSeconds"),
    )
    min_improvement_over_baseline: float = Field(
        default=0.0,
        ge=-1,
        le=1,
        validation_alias=AliasChoices("min_improvement_over_baseline", "minImprovementOverBaseline"),
    )
    random_state: int = Field(
        default=42,
        ge=0,
        validation_alias=AliasChoices("random_state", "randomState"),
    )


class DelayModelTrainingResult(BaseModel):
    model_config = ConfigDict(frozen=True)

    artifact: DelayModelArtifact
    train_source_ids: list[str]
    validation_source_ids: list[str]


def train_delay_model(
    examples: list[DelayTrainingExample],
    config: DelayModelTrainingConfig | None = None,
) -> DelayModelTrainingResult:
    config = config or DelayModelTrainingConfig()
    if len(examples) < 4:
        raise ValueError("at least four delay training examples are required")

    ordered = sorted(examples, key=lambda example: example.source_id)
    validation_count = max(config.min_validation_examples, round(len(ordered) * config.validation_fraction))
    validation_count = min(validation_count, len(ordered) - 2)
    train_examples = ordered[:-validation_count]
    validation_examples = ordered[-validation_count:]

    x_train = _design_matrix([example.feature for example in train_examples])
    y_train = np.asarray([example.observed_delay_seconds for example in train_examples], dtype=float)
    from sklearn import __version__ as sklearn_version
    from sklearn.dummy import DummyRegressor
    from sklearn.linear_model import Ridge
    from sklearn.metrics import mean_absolute_error, mean_squared_error

    model = Ridge(alpha=config.regularization_alpha, random_state=config.random_state)
    model.fit(x_train, y_train)
    baseline_model = DummyRegressor(strategy="mean")
    baseline_model.fit(x_train, y_train)

    x_validation = _design_matrix([example.feature for example in validation_examples])
    y_validation = np.asarray([example.observed_delay_seconds for example in validation_examples], dtype=float)
    predictions = np.maximum(0, model.predict(x_validation))
    baseline_predictions = np.maximum(0, baseline_model.predict(x_validation))
    metrics = _evaluate(
        y_validation,
        predictions,
        baseline_predictions,
        len(examples),
        len(train_examples),
        len(validation_examples),
        mean_absolute_error=mean_absolute_error,
        mean_squared_error=mean_squared_error,
    )
    release_gate = _release_gate(metrics, config)
    model_version = config.model_version or f"vrp-delay-sklearn-ridge-{datetime.now(UTC).strftime('%Y%m%d%H%M%S')}"

    artifact = DelayModelArtifact(
        model_version=model_version,
        trainer_version=sklearn_version,
        feature_names=list(DELAY_MODEL_FEATURE_NAMES),
        intercept=float(model.intercept_),
        coefficients=[float(value) for value in np.asarray(model.coef_, dtype=float)],
        metrics=metrics,
        release_gate=release_gate,
        served_to_users=False,
    )
    return DelayModelTrainingResult(
        artifact=artifact,
        train_source_ids=[example.source_id for example in train_examples],
        validation_source_ids=[example.source_id for example in validation_examples],
    )


def predict_delay_seconds(artifact: DelayModelArtifact, feature: EdgeFeatureVector) -> float:
    values = np.asarray(vectorize_delay_features(feature, artifact.feature_names), dtype=float)
    coefficients = np.asarray(artifact.coefficients, dtype=float)
    if len(values) != len(coefficients):
        raise ValueError("feature vector length does not match artifact coefficients")
    return max(0.0, float(artifact.intercept + values @ coefficients))


def _design_matrix(features: list[EdgeFeatureVector]) -> np.ndarray:
    rows = [vectorize_delay_features(feature) for feature in features]
    return np.asarray(rows, dtype=float)


def _evaluate(
    y_validation: np.ndarray,
    predictions: np.ndarray,
    baseline_predictions: np.ndarray,
    example_count: int,
    train_count: int,
    validation_count: int,
    *,
    mean_absolute_error,
    mean_squared_error,
) -> DelayModelMetrics:
    absolute_errors = np.abs(y_validation - predictions)
    baseline_errors = np.abs(y_validation - baseline_predictions)
    mae = float(mean_absolute_error(y_validation, predictions))
    baseline_mae = float(mean_absolute_error(y_validation, baseline_predictions))
    improvement = 0.0 if baseline_mae == 0 else (baseline_mae - mae) / baseline_mae
    return DelayModelMetrics(
        example_count=example_count,
        train_count=train_count,
        validation_count=validation_count,
        mae_seconds=mae,
        rmse_seconds=float(np.sqrt(mean_squared_error(y_validation, predictions))),
        p95_abs_error_seconds=float(np.percentile(absolute_errors, 95)),
        baseline_mae_seconds=baseline_mae,
        improvement_over_baseline=improvement,
    )


def _release_gate(metrics: DelayModelMetrics, config: DelayModelTrainingConfig) -> DelayModelReleaseGate:
    reasons: list[str] = []
    if metrics.validation_count < config.min_validation_examples:
        reasons.append("not enough validation examples")
    if metrics.mae_seconds > config.max_mae_seconds:
        reasons.append("validation MAE is above release threshold")
    if metrics.improvement_over_baseline < config.min_improvement_over_baseline:
        reasons.append("model does not beat the baseline delay predictor")
    return DelayModelReleaseGate(
        passed=not reasons,
        min_validation_examples=config.min_validation_examples,
        max_mae_seconds=config.max_mae_seconds,
        min_improvement_over_baseline=config.min_improvement_over_baseline,
        reasons=reasons,
    )
