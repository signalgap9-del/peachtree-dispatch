# ADR 0001: Use a Low-Fixed-Cost AWS Architecture

## Status

Accepted

## Context

The portfolio must demonstrate production AWS and DevOps practices while remaining inexpensive to operate. Always-on Kubernetes, NAT Gateway, load balancers, and relational databases would consume the budget before providing proportionate portfolio value.

## Decision

Use API Gateway, Lambda, EventBridge, SQS, Step Functions, DynamoDB, CloudFront, and S3 for the first production-style release. Add an on-demand ECS Fargate task for a bounded batch workload, but avoid always-on container infrastructure.

Use Terraform for infrastructure and GitHub Actions OIDC for deployment.

## Consequences

- The platform can demonstrate distributed workflow reliability at low idle cost.
- The repository can focus on CI/CD, security, observability, and incident response.
- Container delivery is demonstrated with an on-demand task rather than an always-on service.
