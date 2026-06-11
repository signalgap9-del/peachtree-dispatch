# AtmosPath

A climate-aware route optimization platform built to demonstrate production-style AWS, DevOps, and SRE practices.

The map-first weather-aware navigation product combines live Open-Meteo forecasts, OSRM road geometry,
and an OR-Tools capacitated vehicle-routing solver in a MapLibre interface
inspired by modern mobility products.

## Live Development Environment

- Web: https://d23c97ytqgl4xu.cloudfront.net
- API health: https://6zi4acjsz2.execute-api.us-east-1.amazonaws.com/health
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

The local environment uses SQLite behind the same repository contract used by
the deployed DynamoDB adapter. AWS optimization requests are queued through SQS
and solved by a dedicated Lambda worker.

See [docs/local-development.md](docs/local-development.md) for the local-to-AWS component mapping and verification commands.

## Local AWS access

This repository uses temporary browser-login credentials instead of long-lived access keys.

```powershell
./scripts/aws-login.ps1 -Profile awsresume-admin
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
