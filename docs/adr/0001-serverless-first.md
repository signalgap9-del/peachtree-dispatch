# ADR 0001: Use a Serverless-First AWS Architecture

## Status

Accepted

## Context

The portfolio must demonstrate production AWS and DevOps practices while remaining inexpensive to operate. Always-on Kubernetes, NAT Gateway, load balancers, and relational databases would consume the budget before providing proportionate portfolio value.

## Decision

Use API Gateway, Lambda, EventBridge, SQS, Step Functions, DynamoDB, CloudFront, and S3 for the first production-style release.

Use Terraform for infrastructure and GitHub Actions OIDC for deployment.

## Consequences

- The platform can demonstrate distributed workflow reliability at low idle cost.
- The repository can focus on CI/CD, security, observability, and incident response.
- Container orchestration remains an optional later migration exercise rather than a prerequisite.
