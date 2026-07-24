# Optimization DLQ Replay Runbook

## Trigger

Alarm `peachtree-dispatch-dev-optimization-dlq-visible` fires when
`ApproximateNumberOfMessagesVisible > 0` on the DLQ.

## Diagnosis

1. Check DLQ depth:
   ```powershell
   ./scripts/aws.ps1 sqs get-queue-attributes `
     --queue-url https://sqs.us-east-1.amazonaws.com/<account-id>/peachtree-dispatch-dev-optimization-dlq `
     --attribute-names ApproximateNumberOfMessages --output table
   ```
2. Peek at failed messages (non-destructive):
   ```powershell
   ./scripts/aws.ps1 sqs receive-message `
     --queue-url https://sqs.us-east-1.amazonaws.com/<account-id>/peachtree-dispatch-dev-optimization-dlq `
     --max-number-of-messages 5 --visibility-timeout 0 --attribute-names All
   ```
3. Check worker logs: `./scripts/aws.ps1 logs filter-log-events --log-group-name /aws/lambda/peachtree-dispatch-dev-optimizer --filter-pattern "ERROR" --start-time <epoch-ms>`
4. Verify referenced jobs exist: `./scripts/aws.ps1 dynamodb get-item --table-name peachtree-dispatch-dev --key '{"PK":{"S":"JOB#<jobId>"},"SK":{"S":"META"}}'`

## Pre-Replay Checklist

- [ ] Root cause fixed and worker deployed via `deploy-dev`.
- [ ] `peachtree-dispatch-dev-optimizer-errors` alarm is `OK`.
- [ ] Failed messages reference jobs in a retryable state.
- [ ] No active deployment or rollback in progress.

## Replay

```powershell
./scripts/aws.ps1 sqs start-message-move-task `
  --source-arn arn:aws:sqs:us-east-1:<account-id>:peachtree-dispatch-dev-optimization-dlq `
  --max-number-of-messages-per-second 10
```
Monitor: `./scripts/aws.ps1 sqs list-message-move-tasks --source-arn <same-arn>`

## Post-Replay Verification

1. DLQ depth returns to 0; alarm transitions to `OK`.
2. Each replayed job reaches `SUCCEEDED` or an explained terminal failure.
3. Operations dashboard queue-depth widget returns to baseline.
4. If messages fail again, do **not** replay a second time; escalate.

Worker uses partial batch failure reporting; successful messages in a batch are
not retried when another record fails.
