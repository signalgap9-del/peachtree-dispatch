from __future__ import annotations

import math
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
    weights = _fit_linear_model(x_train, y_train, config.regularization_alpha)

    x_validation = _design_matrix([example.feature for example in validation_examples])
    y_validation = np.asarray([example.observed_delay_seconds for example in validation_examples], dtype=float)
    predictions = np.maximum(0, _predict_matrix(x_validation, weights))
    metrics = _evaluate(y_train, y_validation, predictions, len(examples), len(train_examples), len(validation_examples))
    release_gate = _release_gate(metrics, config)
    model_version = config.model_version or f"vrp-delay-linear-{datetime.now(UTC).strftime('%Y%m%d%H%M%S')}"

    artifact = DelayModelArtifact(
        model_version=model_version,
        feature_names=list(DELAY_MODEL_FEATURE_NAMES),
        intercept=float(weights[0]),
        coefficients=[float(value) for value in weights[1:]],
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
    values = np.asarray([1.0, *vectorize_delay_features(feature, artifact.feature_names)], dtype=float)
    coefficients = np.asarray([artifact.intercept, *artifact.coefficients], dtype=float)
    return max(0.0, float(values @ coefficients))


def _design_matrix(features: list[EdgeFeatureVector]) -> np.ndarray:
    rows = [[1.0, *vectorize_delay_features(feature)] for feature in features]
    return np.asarray(rows, dtype=float)


def _fit_linear_model(x: np.ndarray, y: np.ndarray, alpha: float) -> np.ndarray:
    if alpha <= 0:
        return np.linalg.lstsq(x, y, rcond=None)[0]
    penalty = math.sqrt(alpha) * np.eye(x.shape[1])
    penalty[0, 0] = 0
    x_augmented = np.vstack([x, penalty])
    y_augmented = np.concatenate([y, np.zeros(x.shape[1])])
    return np.linalg.lstsq(x_augmented, y_augmented, rcond=None)[0]


def _predict_matrix(x: np.ndarray, weights: np.ndarray) -> np.ndarray:
    return x @ weights


def _evaluate(
    y_train: np.ndarray,
    y_validation: np.ndarray,
    predictions: np.ndarray,
    example_count: int,
    train_count: int,
    validation_count: int,
) -> DelayModelMetrics:
    absolute_errors = np.abs(y_validation - predictions)
    baseline_prediction = float(np.mean(y_train))
    baseline_errors = np.abs(y_validation - baseline_prediction)
    mae = float(np.mean(absolute_errors))
    baseline_mae = float(np.mean(baseline_errors))
    improvement = 0.0 if baseline_mae == 0 else (baseline_mae - mae) / baseline_mae
    return DelayModelMetrics(
        example_count=example_count,
        train_count=train_count,
        validation_count=validation_count,
        mae_seconds=mae,
        rmse_seconds=float(np.sqrt(np.mean(np.square(y_validation - predictions)))),
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
