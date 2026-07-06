from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.vrp.ml.artifact import load_delay_model_artifact, save_delay_model_artifact


def main() -> int:
    parser = argparse.ArgumentParser(description="Promote a gated AtmosPath VRP delay model artifact for served route-cost evaluation.")
    parser.add_argument("--input", required=True, type=Path, help="Existing delay model artifact JSON path.")
    parser.add_argument("--output", required=True, type=Path, help="Promoted artifact output JSON path.")
    args = parser.parse_args()

    artifact = load_delay_model_artifact(args.input)
    if not artifact.release_gate.passed:
        print(
            json.dumps({
                "promoted": False,
                "modelVersion": artifact.model_version,
                "releaseGatePassed": False,
                "releaseGateReasons": artifact.release_gate.reasons,
            }, indent=2),
            file=sys.stderr,
        )
        return 2

    promoted = artifact.model_copy(update={"served_to_users": True})
    save_delay_model_artifact(args.output, promoted)
    print(json.dumps({
        "promoted": True,
        "modelVersion": promoted.model_version,
        "artifactPath": str(args.output),
        "releaseGatePassed": promoted.release_gate.passed,
        "servedToUsers": promoted.served_to_users,
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
