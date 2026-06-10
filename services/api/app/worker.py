import json
import traceback
from datetime import UTC, datetime

from .models import OptimizationJob, OptimizationStatus
from .network import build_network
from .optimization_service import OptimizationService
from .repository_factory import create_repository


def handler(event: dict, context: object) -> dict:
    repository = create_repository()
    service = OptimizationService()
    failures: list[dict[str, str]] = []
    for record in event.get("Records", []):
        message_id = record["messageId"]
        job_id = json.loads(record["body"])["job_id"]
        job = service.get(job_id)
        if not job:
            failures.append({"itemIdentifier": message_id})
            continue
        try:
            service.save(
                job.model_copy(
                    update={
                        "status": OptimizationStatus.RUNNING,
                        "updated_at": datetime.now(UTC),
                    }
                )
            )
            result = build_network(repository.list())
            service.save(
                job.model_copy(
                    update={
                        "status": OptimizationStatus.SUCCEEDED,
                        "updated_at": datetime.now(UTC),
                        "result": result,
                    }
                )
            )
        except Exception as error:
            service.save(
                job.model_copy(
                    update={
                        "status": OptimizationStatus.FAILED,
                        "updated_at": datetime.now(UTC),
                        "error": str(error),
                    }
                )
            )
            traceback.print_exc()
            failures.append({"itemIdentifier": message_id})
    return {"batchItemFailures": failures}
