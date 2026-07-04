from __future__ import annotations

import os
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from .features import FEATURE_SCHEMA_VERSION
from .artifact import DelayModelArtifact, load_delay_model_artifact


MLWorkflowMode = Literal["SHADOW_DISABLED", "SHADOW_LOG_ONLY", "SHADOW_EVALUATING", "SERVING_BLOCKED"]


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
    if mode not in {"SHADOW_DISABLED", "SHADOW_LOG_ONLY", "SHADOW_EVALUATING", "SERVING_BLOCKED"}:
        mode = "SHADOW_DISABLED"
    artifact = _load_configured_artifact()
    return MLWorkflowStatus(
        mode=mode,
        served_to_users=False,
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
                detail="NumPy least-squares baseline trainer can produce artifact-backed delay predictions.",
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
        ],
        next_actions=[
            "Export cross-route saved-route ML datasets from the platform API.",
            "Join route observations to NWS alert geometry, weather raster samples, and WZDx road events.",
            "Log observed-vs-predicted delay in shadow mode before enabling any served cost changes.",
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
