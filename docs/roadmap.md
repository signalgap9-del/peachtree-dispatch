# Delivery Roadmap

## Phase 0: Account and Repository Foundation

- Enable root MFA.
- Add email notifications to the existing monthly budget.
- Create the GitHub repository.
- Bootstrap Terraform remote state in an encrypted, versioned S3 bucket.
- Configure S3 state locking.
- Configure GitHub OIDC with separate plan and deploy roles.
- Add branch protection and a GitHub `dev` environment approval gate.

## Phase 1: CI and Infrastructure Skeleton

- Add Terraform provider/version constraints and common tags.
- Add `terraform fmt`, `validate`, TFLint, Checkov, and Trivy checks.
- Run Terraform plan on pull requests.
- Apply only from protected `main` after approval.
- Add Dependabot and pre-commit hooks.

## Phase 2: Minimum Viable Platform

- Review and approve product requirements, access patterns, and ADRs.
- Build the React MapLibre climate-routing experience.
- Add live forecast risk and road geometry provider adapters.
- Benchmark and tune the OR-Tools VRP solver.
- Add authentication before exposing write operations publicly.
- Implement delivery create/read/update API operations.
- Store delivery state in DynamoDB.
- Deploy CloudFront, S3, API Gateway, and Lambda with Terraform.
- Add unit and deployed smoke tests.

## Phase 3: Event-Driven Operations

- Process asynchronous optimization work through SQS and Lambda.
- Add retries, idempotency, DLQ, and replay tooling.
- Demonstrate a failed-delivery incident scenario.
- Evaluate Step Functions or ECS Fargate only when a measured workload requires them.

## Phase 4: SRE and DevSecOps

- Add CloudWatch dashboards, alarms, structured logs, and X-Ray traces.
- Define SLIs and an SLO for API availability and workflow success.
- Add a runbook and an incident postmortem.
- Add IAM least-privilege review and security scanning.
- Add backup and restore verification.

## Phase 5: Portfolio Packaging

- Add an architecture diagram and short demo video.
- Add screenshots of CI plans, dashboards, alarms, and incident recovery.
- Publish cost breakdown and security decisions.
- Write resume bullets with measured outcomes.
- Document the Lambda versus Fargate workload comparison using measured cost and runtime data.

## GitHub Evidence Checklist

- Pull request showing Terraform plan and review
- Successful CI/CD workflow using OIDC
- CloudWatch dashboard screenshot
- DLQ failure and replay demonstration
- Incident runbook and postmortem
- Cost estimate and monthly budget
- Clear architecture decision records
