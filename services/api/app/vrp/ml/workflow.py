from __future__ import annotations

import os
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from .features import FEATURE_SCHEMA_VERSION
from .artifact import DelayModelArtifact, load_delay_model_artifact


MLWorkflowMode = Literal["SHADOW_DISABLED", "SHADOW_LOG_ONLY", "SHADOW_EVALUATING", "SERVING_BLOCKED", "SERVING_ENABLED"]


class TrainingReadinessCheck(BaseModel):
    model_config = ConfigDict(frozen=True)

    label: str
    ready: bool
    detail: str


class MLWorkflowStatus(BaseModel):
    model_config = ConfigDict(frozen=True)

    mode: MLWorkflowMode
    served_to_users: bool
    active_model_version: str
    feature_schema_version: str
    training_readiness: list[TrainingReadinessCheck]
    next_actions: list[str] = Field(default_factory=list)


def get_ml_workflow_status() -> MLWorkflowStatus:
    mode = os.getenv("VRP_ML_WORKFLOW_MODE", "SHADOW_DISABLED")
    if mode not in {"SHADOW_DISABLED", "SHADOW_LOG_ONLY", "SHADOW_EVALUATING", "SERVING_BLOCKED", "SERVING_ENABLED"}:
        mode = "SHADOW_DISABLED"
    artifact = _load_configured_artifact()
    allow_served_cost = os.getenv("VRP_ML_ALLOW_SERVED_COST", "").lower() in {"1", "true", "yes"}
    served_to_users = bool(
        mode == "SERVING_ENABLED"
        and allow_served_cost
        and artifact
        and artifact.served_to_users
        and artifact.release_gate.passed
    )
    return MLWorkflowStatus(
        mode=mode,
        served_to_users=served_to_users,
        active_model_version=artifact.model_version if artifact else os.getenv("VRP_ML_MODEL_VERSION", "none"),
        feature_schema_version=FEATURE_SCHEMA_VERSION,
        training_readiness=[
            TrainingReadinessCheck(
                label="route_observations",
                ready=True,
                detail="Spring platform API persists saved-route planned-vs-actual observations for ML labels.",
            ),
            TrainingReadinessCheck(
                label="baseline_trainer",
                ready=True,
                detail="scikit-learn Ridge trainer backtests against a DummyRegressor baseline and exports a JSON artifact.",
            ),
            TrainingReadinessCheck(
                label="model_artifact",
                ready=artifact is not None,
                detail=(
                    f"Loaded artifact {artifact.model_version}."
                    if artifact
                    else "Set VRP_ML_MODEL_ARTIFACT to enable artifact-backed shadow prediction."
                ),
            ),
            TrainingReadinessCheck(
                label="backtest_gate",
                ready=artifact.release_gate.passed if artifact else False,
                detail=(
                    "Configured artifact passed its offline release gate."
                    if artifact and artifact.release_gate.passed
                    else "Backtest gate must pass before any serving rollout is considered."
                ),
            ),
            TrainingReadinessCheck(
                label="served_cost_guard",
                ready=served_to_users,
                detail=(
                    "Artifact is promoted, release gate passed, workflow mode is SERVING_ENABLED, and VRP_ML_ALLOW_SERVED_COST is enabled."
                    if served_to_users
                    else "Serving requires a promoted artifact, passed release gate, SERVING_ENABLED mode, and VRP_ML_ALLOW_SERVED_COST=true."
                ),
            ),
        ],
        next_actions=[
            "Export cross-route saved-route ML datasets from the platform API.",
            "Join route observations to NWS alert geometry, weather raster samples, and WZDx road events.",
            (
                "Monitor served ML route-cost decisions against rule-based alternatives and be ready to return to shadow mode."
                if served_to_users
                else "Log observed-vs-predicted delay in shadow mode before enabling any served cost changes."
            ),
        ],
    )


def _load_configured_artifact() -> DelayModelArtifact | None:
    artifact_path = os.getenv("VRP_ML_MODEL_ARTIFACT")
    if not artifact_path:
        return None
    path = Path(artifact_path)
    if not path.exists():
        return None
    return load_delay_model_artifact(path)
