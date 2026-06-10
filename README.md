# Peachtree Dispatch

An event-driven delivery operations platform built to demonstrate production-style AWS, DevOps, and SRE practices.

## Portfolio Goals

- Provision all application infrastructure with Terraform.
- Deploy through GitHub Actions using OIDC and short-lived AWS credentials.
- Demonstrate event-driven architecture, failure handling, observability, and security.
- Keep the development environment inexpensive enough to run as a public portfolio project.

See [docs/architecture.md](docs/architecture.md) and [docs/roadmap.md](docs/roadmap.md).

## Repository Automation

- Pull requests validate and security-scan Terraform.
- Infrastructure pull requests generate a remote Terraform plan through GitHub OIDC.
- Development applies run manually through a protected GitHub environment.
- Dependabot maintains GitHub Actions and Terraform dependencies.

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
