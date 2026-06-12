# AtmosPath

[![CI](https://github.com/signalgap9-del/peachtree-dispatch/actions/workflows/ci.yml/badge.svg)](https://github.com/signalgap9-del/peachtree-dispatch/actions/workflows/ci.yml)
[![Deploy Dev](https://github.com/signalgap9-del/peachtree-dispatch/actions/workflows/deploy-dev.yml/badge.svg)](https://github.com/signalgap9-del/peachtree-dispatch/actions/workflows/deploy-dev.yml)

AtmosPath is a nationwide weather-risk navigation platform. It compares route
alternatives, overlays live weather and hazard signals, and explains why a
safer route may differ from the fastest route.

**[Open the live AWS deployment](https://d23c97ytqgl4xu.cloudfront.net/)** |
**[Architecture](docs/architecture.md)** |
**[Cost model](docs/cost-model.md)** |
**[ADRs](docs/adr/)**

![AtmosPath nationwide route comparison](docs/design/mockups/nationwide-route-compare-desktop.png)

## Engineering Highlights

- Serverless AWS architecture designed for a sub-$5/month portfolio workload.
- React, TypeScript, MapLibre, Spring Boot, Java 21, FastAPI, and OR-Tools.
- Cognito authentication with owner-scoped DynamoDB saved-place persistence.
- CloudFront private S3 origin, API Gateway, Lambda, SQS, DLQ, S3, and CloudWatch.
- Terraform-managed dev and production environments.
- Secretless GitHub Actions delivery through AWS OIDC.
- CI gates for unit, browser, container, PostGIS schema, dependency, IaC, and security checks.

## Live Environment

- Web: https://d23c97ytqgl4xu.cloudfront.net/
- API health: https://d23c97ytqgl4xu.cloudfront.net/api/health
- Access is geo-restricted to the United States and South Korea.
- Public browsing is enabled; authenticated writes remain protected by Cognito.

## Portfolio Goals

- Provision all application infrastructure with Terraform.
- Deploy through GitHub Actions using OIDC and short-lived AWS credentials.
- Demonstrate event-driven architecture, failure handling, observability, and security.
- Keep the development environment inexpensive enough to run as a public portfolio project.

See [docs/architecture.md](docs/architecture.md) and [docs/roadmap.md](docs/roadmap.md).

## Repository Automation

- Pull requests validate and security-scan Terraform.
- Infrastructure pull requests generate a remote Terraform plan through GitHub OIDC.
- `main` deploys the dev environment with an immutable Lambda image.
- Production promotion and rollback are manual, approval-gated workflows.
- Dependabot maintains GitHub Actions and Terraform dependencies.

## Run Locally

Requirements: Docker Desktop.

```powershell
docker compose up --build
```

Open:

- Climate routing map: `http://localhost:5173`
- API documentation: `http://localhost:8000/docs`
- API health: `http://localhost:8000/health`

The current Python service retains a local SQLite adapter for legacy operational
prototype code. The deployed Spring Boot API uses DynamoDB for authenticated
saved places as well as bounded jobs, caches, idempotency, deduplication, and
snapshot pointers.

An optional PostGIS Compose profile and Aurora Serverless v2/Data API path
demonstrate a future high-scale spatial-query path. Aurora remains disabled by
default so the portfolio preview has no relational database idle cost.

See [docs/local-development.md](docs/local-development.md) for the local-to-AWS component mapping and verification commands.

## AWS access

Routine deployments use GitHub Actions OIDC and require no local AWS login or
long-lived access keys. Local browser login is a break-glass operation only:

```powershell
./scripts/aws-login.ps1 -Profile bootstrap -BreakGlass
./scripts/aws.ps1 sts get-caller-identity
```

The default profile and region are:

- Profile: `bootstrap` until IAM Identity Center onboarding is complete
- Region: `us-east-1`

## Safety

- Never commit AWS credentials or local environment files.
- Manage deployable infrastructure as code.
- Review cost impact before provisioning paid resources.
- Enable MFA on the AWS account root user before routine development.
