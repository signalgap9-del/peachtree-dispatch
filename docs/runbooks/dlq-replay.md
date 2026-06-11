# Optimization DLQ Replay Runbook

## Trigger

Replay when the optimization DLQ alarm enters `ALARM` and the underlying cause
has been fixed.

## Procedure

1. Inspect worker Lambda errors and the failed message body.
2. Verify the referenced optimization job still exists in DynamoDB.
3. Fix and deploy the worker before replaying.
4. Use SQS redrive to move messages from the DLQ to the source queue.
5. Confirm each job reaches `SUCCEEDED` or an explained terminal failure.
6. Confirm DLQ depth returns to zero.

Worker processing uses partial batch failure reporting. Successful messages are
not retried when another record in the same batch fails.
