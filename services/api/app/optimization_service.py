import json
import os
from datetime import UTC, datetime
from decimal import Decimal
from threading import Lock
from uuid import uuid4

import boto3

from .models import OptimizationJob, OptimizationStatus


class OptimizationService:
    def __init__(self) -> None:
        self.queue_url = os.getenv("OPTIMIZATION_QUEUE_URL")
        self.table_name = os.getenv("DYNAMODB_TABLE")
        self.sqs = boto3.client("sqs", region_name="us-east-1") if self.queue_url else None
        self.table = (
            boto3.resource("dynamodb", region_name="us-east-1").Table(self.table_name)
            if self.table_name
            else None
        )
        self.jobs: dict[str, OptimizationJob] = {}
        self.lock = Lock()

    def submit(self) -> OptimizationJob:
        now = datetime.now(UTC)
        job = OptimizationJob(
            job_id=f"opt-{uuid4().hex[:12]}",
            status=OptimizationStatus.QUEUED,
            created_at=now,
            updated_at=now,
        )
        self.save(job)
        if self.sqs and self.queue_url:
            self.sqs.send_message(
                QueueUrl=self.queue_url,
                MessageBody=json.dumps({"job_id": job.job_id}),
            )
        return job

    def get(self, job_id: str) -> OptimizationJob | None:
        if self.table:
            response = self.table.get_item(
                Key={"PK": f"OPTIMIZATION#{job_id}", "SK": "META"},
                ConsistentRead=True,
            )
            return (
                OptimizationJob.model_validate(response["Item"]) if "Item" in response else None
            )
        return self.jobs.get(job_id)

    def save(self, job: OptimizationJob) -> None:
        if self.table:
            item = json.loads(job.model_dump_json(), parse_float=Decimal)
            item.update({"PK": f"OPTIMIZATION#{job.job_id}", "SK": "META"})
            self.table.put_item(Item=item)
            return
        with self.lock:
            self.jobs[job.job_id] = job
