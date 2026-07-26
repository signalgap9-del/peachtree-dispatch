# DynamoDB Recovery Runbook

## When to Use

Accidental item deletion/overwrite, data corruption, or a bad migration that
cannot be fixed in place. **RPO < 5 min, RTO < 60 min.** PITR is enabled on
all operational tables.

## Automated Drill Script

A validated, repeatable drill procedure is available at
[`scripts/dr_restore_test.ps1`](../../scripts/dr_restore_test.ps1). It
automates the full restore-verify-test-teardown cycle and records RTO/RPO
metrics. Run it quarterly or after any schema change:

```powershell
# Full drill (restore + verify + contract tests + teardown)
./scripts/dr_restore_test.ps1

# Quick count-only check (skip pytest, keep table for inspection)
./scripts/dr_restore_test.ps1 -SkipContractTests -KeepTestTable

# Restore to a specific timestamp
./scripts/dr_restore_test.ps1 -RestoreDateTime "2026-07-27T03:00:00Z"
```

The script appends results to `docs/dr-drill-results.md`. See
[`docs/dr-game-day.md`](../dr-game-day.md) for the full game-day runbook
including multi-failure scenarios.

## PITR Restore

1. Identify the last known-good timestamp (before the bad write).
2. Restore to a new table:
   ```powershell
   ./scripts/aws.ps1 dynamodb restore-table-to-point-in-time `
     --source-table-name peachtree-dispatch-dev `
     --target-table-name peachtree-dispatch-dev-recovery `
     --restore-date-time <YYYY-MM-DDTHH:MM:SSZ>
   ```
3. Wait for completion:
   ```powershell
   ./scripts/aws.ps1 dynamodb wait table-exists --table-name peachtree-dispatch-dev-recovery
   ./scripts/aws.ps1 dynamodb describe-table --table-name peachtree-dispatch-dev-recovery `
     --query 'Table.TableStatus'
   # Expected: "ACTIVE"
   ```

## Verification

1. Compare item counts:
   ```powershell
   ./scripts/aws.ps1 dynamodb describe-table --table-name peachtree-dispatch-dev --query 'Table.ItemCount'
   ./scripts/aws.ps1 dynamodb describe-table --table-name peachtree-dispatch-dev-recovery --query 'Table.ItemCount'
   ```
2. Spot-check representative records (snapshot, job, cache, idempotency items).
3. Run contract/smoke tests with `DYNAMODB_TABLE=peachtree-dispatch-dev-recovery`.

## Cutover

1. Point the application at the recovery table (Terraform variable or env var).
2. Deploy via `deploy-dev` and re-run smoke tests.
3. Confirm DLQ and Lambda error alarms stay `OK` for 30 minutes.
4. **Preserve the original table** until the incident is formally closed.
5. Delete the recovery table only after the original is confirmed unnecessary.

> Destructive data migrations are not part of normal deployment. Schema changes
> are backward compatible; this runbook covers data loss only.

## Drill History

| Date | RTO | RPO | Result | Notes |
|------|-----|-----|--------|-------|
| _(template — no drill run yet)_ | — | — | — | Run `scripts/dr_restore_test.ps1` to populate |
