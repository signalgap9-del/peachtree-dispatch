# VRP Delay ML Shadow Model

Date: 2026-07-04

## Objective

AtmosPath route optimization needs a real ML workflow without making unsafe
route decisions before enough field evidence exists. This slice upgrades the
route engine from "ML planned" to "ML shadow evaluation" and adds a guarded
served-cost mode for VRP/multi-stop solver experiments:

- train a delay model from saved-route observations
- run an offline backtest against a baseline model
- write and load a versioned model artifact
- compare ML delay predictions against rule-based edge costs in shadow mode
- optionally apply promoted ML delay to the solver cost matrix when release
  gates, runtime guards, and request flags are all enabled

## Research Direction

- SVRPBench frames the right research problem: stochastic routing with
  time-dependent congestion, probabilistic incidents, empirically grounded time
  windows, multi-depot and multi-vehicle scenarios.
- PyVRP is the right next solver candidate for production-grade constraints:
  time windows, service durations, vehicle shift duration, overtime, multiple
  depots, heterogeneous fleets, and optional clients.
- NWS alerts and WZDx/511 road events are the right authoritative risk feeds for
  weather and road-condition features.

References:

- [SVRPBench paper](https://arxiv.org/abs/2505.21887)
- [SVRPBench dataset](https://huggingface.co/datasets/Yahias21/vrp_benchmark)
- [PyVRP documentation](https://pyvrp.org/)
- [National Weather Service API documentation](https://www.weather.gov/documentation/services-web-api)
- [USDOT WZDx overview](https://www.transportation.gov/av/data/wzdx)
- [MLflow XGBoost documentation](https://mlflow.org/docs/latest/ml/traditional-ml/xgboost/)

## Current Model

Model:

- `sklearn-ridge-delay-regression`
- trainer backend: `scikit-learn`
- baseline: `DummyRegressor(strategy="mean")`
- label: positive delay seconds
- feature schema: `edge-cost-v1`

The runtime artifact is JSON, not pickle/joblib. This is intentional. Pickle and
joblib artifacts can execute code when loaded, so the serving path stores only
coefficients, intercept, feature names, metrics, and release-gate metadata.

## Feature Inputs

Current feature groups:

- base route duration
- base route distance
- weather risk score
- traffic risk score
- flood risk score
- active alert risk score
- max risk score
- primary hazard present
- vehicle type one-hot encoding

Next feature groups:

- NWS alert geometry intersection by edge corridor
- WZDx/511 roadwork, closure, crash, and detour joins
- flood/rainfall raster samples
- observed speed and congestion buckets
- time-of-day, day-of-week, and seasonality
- black-ice and freezing-rain indicators

## Release Gate

The model artifact records:

- train/validation counts
- MAE
- RMSE
- p95 absolute error
- mean-delay baseline MAE
- improvement over baseline
- release gate pass/fail and reasons

Training artifacts default to `served_to_users=false`. A model can affect
solver decisions only after a separate promotion step and runtime guard:

1. Train/backtest the model and pass the release gate.
2. Promote the artifact with `scripts/promote_vrp_delay_model.py`.
3. Configure `VRP_ML_MODEL_ARTIFACT` to the promoted artifact.
4. Set `VRP_ML_WORKFLOW_MODE=SERVING_ENABLED`.
5. Set `VRP_ML_ALLOW_SERVED_COST=true`.
6. Send a VRP or multi-stop request with `useMlServedCost=true`.

If any condition is missing, the route engine still records `ml_delay_seconds`
when requested but keeps rule-based adjusted cost authoritative.

When served cost is enabled, the predicted delay is confidence-gated, capped by
`mlMaxDelaySeconds`, weighted by `mlDelayWeight`, and added to the risk-adjusted
matrix that OR-Tools or the multi-stop optimizer uses.

## API Surface

- `GET /ml/vrp/workflow/status`
- `POST /ml/vrp/delay-model/backtest`
- `POST /ml/vrp/delay-model/predict`

## CLI

```powershell
python scripts/train_vrp_delay_model.py `
  --input .\data\saved-route-observations.json `
  --output .\artifacts\vrp-delay-model.json `
  --model-version vrp-delay-YYYYMMDD `
  --max-mae-seconds 900

python scripts/promote_vrp_delay_model.py `
  --input .\artifacts\vrp-delay-model.json `
  --output .\artifacts\vrp-delay-model-served.json
```

## Optional Research Dependencies

`services/api/requirements-ml.txt` includes MLflow and XGBoost for offline
experiments. They are intentionally not required for the default route-serving
runtime because they increase package size and cold-start risk.

Use MLflow/XGBoost in a separate training environment when comparing models,
then export a safe runtime artifact only after a backtest gate passes.

## Next Slices

1. Persist edge-level observations from saved-route check-ins.
2. Add NWS alert and WZDx/511 corridor joins to edge features.
3. Log served ML decisions next to rule-based alternatives before widening rollout.
4. Add PyVRP adapter for time windows, service durations, and vehicle shifts.
5. Add SVRPBench-style synthetic benchmark harness.
6. Add Dispatch Optimizer UI backed by real VRP scenarios.
