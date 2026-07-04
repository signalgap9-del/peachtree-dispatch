from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.vrp.ml.artifact import save_delay_model_artifact
from app.vrp.ml.dataset import load_saved_route_training_examples, saved_route_examples_to_delay_dataset
from app.vrp.ml.trainer import DelayModelTrainingConfig, train_delay_model


def main() -> int:
    parser = argparse.ArgumentParser(description="Train the AtmosPath VRP delay shadow model.")
    parser.add_argument("--input", required=True, type=Path, help="JSON or JSONL saved-route ML dataset.")
    parser.add_argument("--output", required=True, type=Path, help="Output model artifact JSON path.")
    parser.add_argument("--model-version", required=True, help="Version string stored in the artifact.")
    parser.add_argument("--validation-fraction", type=float, default=0.25)
    parser.add_argument("--regularization-alpha", type=float, default=0.1)
    parser.add_argument("--max-mae-seconds", type=float, default=900)
    parser.add_argument("--min-improvement-over-baseline", type=float, default=0.0)
    args = parser.parse_args()

    source_examples = load_saved_route_training_examples(args.input)
    delay_examples = saved_route_examples_to_delay_dataset(source_examples)
    result = train_delay_model(
        delay_examples,
        DelayModelTrainingConfig(
            model_version=args.model_version,
            validation_fraction=args.validation_fraction,
            regularization_alpha=args.regularization_alpha,
            max_mae_seconds=args.max_mae_seconds,
            min_improvement_over_baseline=args.min_improvement_over_baseline,
        ),
    )
    save_delay_model_artifact(args.output, result.artifact)
    print(json.dumps({
        "modelVersion": result.artifact.model_version,
        "artifactPath": str(args.output),
        "exampleCount": result.artifact.metrics.example_count,
        "validationMaeSeconds": result.artifact.metrics.mae_seconds,
        "baselineMaeSeconds": result.artifact.metrics.baseline_mae_seconds,
        "releaseGatePassed": result.artifact.release_gate.passed,
        "releaseGateReasons": result.artifact.release_gate.reasons,
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
