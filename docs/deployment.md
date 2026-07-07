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
6. Verifies public web access, API health, DynamoDB output, and Cognito authorization entry point.

The workflow uses GitHub Actions OIDC end to end. It does not depend on a local
AWS browser session.

## Dev Post-Deploy Verification

The deploy workflow already runs automated smoke checks. For a release
stabilization pass, also verify from a local shell:

```powershell
$base = "https://d23c97ytqgl4xu.cloudfront.net"
Invoke-WebRequest "$base/" -UseBasicParsing | Select-Object StatusCode
Invoke-RestMethod "$base/api/health"
Invoke-RestMethod "$base/api/risk/weather-raster" | Select-Object generated_at, expires_at, url
```

Then open:

- `$base/map?origin=Seattle&destination=Miami%20Beach`
- `$base/alerts?q=flood`
- `$base/status`

Expected result:

- web returns HTTP 200;
- API health returns `healthy`;
- weather-raster manifest includes `generated_at`, `expires_at`, and `url`;
- route planning returns alternatives;
- `/status` shows source health and, after the current workflow change, the
  deployed `VITE_GIT_SHA`.

Known-good dev deploy before this stabilization pass:

- commit `61a3d97d3cf3b0563a2eb8bfdb9475a27c0cb7e1`
- GitHub Actions run: https://github.com/signalgap9-del/peachtree-dispatch/actions/runs/28837589265
- conclusion: success

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
