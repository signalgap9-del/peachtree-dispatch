# VRP ML Workflow

Date: 2026-07-04

## Status

Active foundation. GraphQL is deferred, but the ML workflow is not just a TODO anymore: it has a dataset contract, a baseline trainer, an artifact format, a backtest gate, and artifact-backed shadow inference.

The model still does not serve route costs to users. That is intentional. Rule-based risk cost remains authoritative until offline and deployed shadow evidence are good enough.

## Implemented Slice

- `SavedRouteTrainingExamplePayload` validates the Java/Spring `/ml-dataset` response shape.
- Saved-route observations convert to `DelayTrainingExample` rows.
- NumPy least-squares training builds a linear positive-delay baseline.
- Holdout backtest records MAE, RMSE, p95 absolute error, baseline MAE, and improvement over baseline.
- Model artifact JSON can be saved and loaded.
- `ArtifactShadowCostModel` predicts `ml_delay_seconds` while keeping `served_to_users=false`.
- VRP edge costs record ML shadow delay when `useMlShadowCost=true`.
- REST endpoints:
  - `GET /ml/vrp/workflow/status`
  - `POST /ml/vrp/delay-model/backtest`
  - `POST /ml/vrp/delay-model/predict`
- Offline CLI:
  - `python scripts/train_vrp_delay_model.py --input dataset.jsonl --output artifacts/vrp-delay-model.json --model-version vrp-delay-v1`

## Why NumPy Baseline First

The existing risk engine already ships NumPy. A small least-squares baseline is enough to prove the ML workflow without adding heavy runtime dependencies or idle infrastructure. NumPy documents `numpy.linalg.lstsq` as solving least-squares systems and returning the coefficient vector that minimizes residual error. That is the right first artifact-backed baseline before adding scikit-learn, XGBoost, or MLflow.

Sources:

- NumPy least squares: https://numpy.org/doc/stable/reference/generated/numpy.linalg.lstsq.html
- Pydantic model validation: https://docs.pydantic.dev/latest/concepts/models/

## Production Guardrails

- Model artifacts are shadow-only.
- Artifact loading is opt-in through `VRP_ML_MODEL_ARTIFACT`.
- `served_to_users` remains false.
- Backtest gates must pass before a model can be considered for rollout.
- Missing artifacts fall back to `DisabledShadowCostModel`.
- The feature schema is versioned as `edge-cost-v1`.

## Next ML Work

1. Export cross-route datasets from platform API instead of per-route only.
2. Add route/edge overlap features from NWS alert geometry and WZDx road events.
3. Add observed-vs-predicted shadow logging during route planning.
4. Add model registry metadata in S3 or DynamoDB.
5. Add a scikit-learn or XGBoost trainer only after the baseline artifact workflow is stable.
