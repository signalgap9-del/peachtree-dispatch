# ADR-0022: Secrets Management, Encryption at Rest, and GDPR Self-Service

Status: accepted

## Context

Operational maturity reviews flagged four gaps: no centralized secrets
management (config written but not deployed), no customer-managed
encryption at rest, limited dependency scanning cadence, and no
self-service GDPR endpoints. WAF remains explicitly deferred (cost
decision recorded in `.trivyignore`).

## Decisions

1. **Secrets Manager + one customer-managed KMS key per environment**
   (`infra/secrets/`). All application secrets (Lemon Squeezy, Google
   Routes, MapTiler, database, auth) live in Secrets Manager with
   placeholder payloads only; real values are set out-of-band with
   `put-secret-value`. One KMS key (`alias/<project>-<env>-platform`,
   annual rotation) encrypts the secrets and, when its ARN is passed to
   the application module via `kms_key_arn`, also DynamoDB SSE and S3
   default encryption. The key ARN is an opt-in variable so the low-cost
   default (SSE-S3, DynamoDB default encryption) remains available.
2. **Least-privilege secret access** via a scoped IAM policy
   (`GetSecretValue` on exactly the provisioned secrets, `kms:Decrypt` /
   `kms:DescribeKey` on exactly the platform key), attached to the
   application Lambda roles by name.
3. **Scheduled + PR security scanning** (`.github/workflows/security-scan.yml`):
   Trivy filesystem scan (vuln + misconfig + secret scanners, fail on
   HIGH/CRITICAL, honoring `.trivyignore`), `npm audit --audit-level=high`,
   `pip-audit`, and gitleaks. Dependabot now also covers Maven
   (`services/platform-api`) and the new `infra/secrets` Terraform root.
4. **GDPR self-service** in `com.atmospath.platform.compliance`:
   `GET /api/v1/me/data-export` (Article 15/20) and
   `DELETE /api/v1/me/account` (Article 17). Erasure executes the
   member-level procedure from `docs/data-retention.md` section 3.1
   against the JPA model: children first, audit log anonymized (not
   deleted, legitimate-interest trail), subscriptions cancelled but
   retained as 7-year billing evidence, idempotent on repeat calls.
   API keys are never exported (hash-only).

## Consequences

- `infra/secrets` is a standalone root applied before the environment
  root; its `kms_key_arn` output feeds `infra/environments/*`.
- Switching an existing DynamoDB table or S3 bucket to SSE-KMS is an
  in-place update; plan it per environment.
- The compliance endpoints exist only when both `atmospath.auth.enabled`
  and `atmospath.saas.enabled` are true; SecurityConfig matchers make the
  authentication requirement explicit.
- Embedding-metadata scrubbing and cold-archive propagation remain
  runbook steps per the retention policy until those stores have
  first-class repositories.
