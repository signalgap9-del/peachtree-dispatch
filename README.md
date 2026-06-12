# AtmosPath

A climate-aware route optimization platform built to demonstrate production-style AWS, DevOps, and SRE practices.

The map-first weather-aware navigation product combines road-route alternatives,
live weather and hazard signals, explainable risk scoring, and a MapLibre
interface inspired by modern mobility products.

## Live Development Environment

- Web: https://d23c97ytqgl4xu.cloudfront.net
- API health: https://d23c97ytqgl4xu.cloudfront.net/api/health after the next guarded deployment
- API documentation is available locally at `http://localhost:8000/docs`.

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
