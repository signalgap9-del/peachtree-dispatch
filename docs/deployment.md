# Deployment and Promotion

## Environments

| Environment | Terraform state | Release gate | Current status |
| --- | --- | --- | --- |
| dev | `peachtree-dispatch/dev/terraform.tfstate` | Push to `main` or manual workflow | Deployed |
| production | `peachtree-dispatch/prod/terraform.tfstate` | Manual approval in GitHub `production` environment | Code-ready, not applied |

Both environments use the encrypted, versioned S3 state bucket created by
`infra/bootstrap`. GitHub Actions assumes AWS roles through OIDC; no long-lived
AWS access keys are stored in GitHub.

## Dev Delivery

The `Deploy Dev` workflow:

1. Assumes the dev apply role.
2. Builds and pushes an immutable Lambda image tagged with the Git SHA.
3. Applies Terraform with that image URI.
4. Builds the web app with the deployed API URL.
5. Syncs assets to the private S3 origin and invalidates CloudFront.
6. Runs an API health smoke test.

## Production Promotion

The `Promote Production` workflow accepts an immutable image URI that already
passed dev smoke tests. GitHub requires approval before the production role can
be assumed. Terraform enables DynamoDB deletion protection and longer log
retention in production.

Production is intentionally not provisioned for the portfolio's normal idle
period. This demonstrates the release path without paying for duplicate
resources before they are needed.

## Rollback

Run the `Rollback` workflow with the previous known-good immutable image URI.
See [deployment rollback runbook](runbooks/deployment-rollback.md) for checks and
recovery steps.
