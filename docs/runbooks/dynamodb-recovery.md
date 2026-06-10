# DynamoDB Recovery Runbook

Production and development tables have point-in-time recovery enabled.

## Recovery Exercise

1. Select a recovery timestamp before the simulated data-loss event.
2. Restore the table to a new recovery table.
3. Run repository contract and smoke tests against the restored table.
4. Compare item counts and representative delivery timelines.
5. Switch application configuration only after validation.
6. Preserve the original table until the incident is closed.

The target RPO is under five minutes and the documented recovery target is
under sixty minutes for portfolio-scale data.
