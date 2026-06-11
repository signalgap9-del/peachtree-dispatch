# Deployment Rollback Runbook

## Trigger

Roll back when the post-deployment smoke test fails, Lambda errors exceed the
alarm threshold, or the deployed API breaks a documented contract.

## Procedure

1. Stop additional deployments through the matching GitHub environment.
2. Identify the last successful immutable ECR image URI from deployment history.
3. Run the `Roll Back Environment` workflow with that URI.
4. Verify `/health`, `/network`, and asynchronous optimization submission.
5. Confirm Lambda error alarms return to `OK`.
6. Record timeline, impact, root cause, and corrective action in a postmortem.

Terraform changes only the Lambda image reference during a normal rollback.
DynamoDB records remain backward compatible; destructive data migrations are
not part of application deployment.
