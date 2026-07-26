# Secrets Management & Encryption at Rest

This root module provisions the secrets and encryption layer for one
environment. It is written but **not deployed** here; apply it per
environment after review.

## What it creates

- One customer-managed **KMS key** (`alias/<project>-<env>-platform`) with
  annual rotation, used for:
  - Secrets Manager secret encryption (this module),
  - DynamoDB table SSE (application module, `kms_key_arn` variable),
  - S3 bucket default encryption SSE-KMS (application module, same variable).
- **Secrets Manager** secrets with placeholder payloads:
  `lemonsqueezy`, `google-routes`, `maptiler`, `database`, `auth`.
- A least-privilege **IAM policy** (`GetSecretValue` on exactly these
  secrets, `kms:Decrypt`/`kms:DescribeKey` on exactly this key), attached to
  the role names passed in `app_role_names` (the application module's
  `<project>-<env>-api` / `-worker` roles).

## Deploy order

```powershell
# 1. Secrets + KMS first.
cd infra/secrets
terraform init
terraform plan -var environment=dev -var 'app_role_names=["peachtree-dispatch-dev-api","peachtree-dispatch-dev-worker"]'
terraform apply -var environment=dev -var 'app_role_names=["peachtree-dispatch-dev-api","peachtree-dispatch-dev-worker"]'

# 2. Feed the KMS key into the environment so DynamoDB/S3 use SSE-KMS.
cd ../environments/dev
terraform plan -var kms_key_arn=$(terraform -chdir=../../secrets output -raw kms_key_arn)
```

Setting `kms_key_arn` on the application module switches the DynamoDB table
to SSE-KMS and both S3 buckets from SSE-S3 to SSE-KMS. Leaving it empty
keeps the previous low-cost defaults (DynamoDB default encryption, SSE-S3).

## Replacing placeholders with real values

Never put real values in Terraform. After applying, set them out-of-band:

```powershell
./scripts/aws.ps1 secretsmanager put-secret-value `
  --secret-id peachtree-dispatch-dev-lemonsqueezy `
  --secret-string '{"api_key":"...","webhook_secret":"...","store_id":"...","pro_variant_id":"..."}'
```

## How the application reads secrets

**Runtime (AWS):** the platform API reads Secrets Manager through the AWS
SDK (`secretsmanager:GetSecretValue`, granted by the IAM policy above) and
maps each JSON payload onto its existing `@ConfigurationProperties`
bindings (`atmospath.billing.*`, `atmospath.datasource.*`, etc.). Lambda
deployments can alternatively reference secret values directly in
environment variables via CloudFormation dynamic references; the Spring
Boot binding names stay the same either way.

**Local development:** secrets come from environment variables only, with
empty/safe defaults in `application.yml`:

```powershell
$env:LEMONSQUEEZY_API_KEY = "local-test-key"
$env:DATASOURCE_PASSWORD  = "atmospath"
```

No code path reads real secrets from disk or from this repository.

## Cost note

KMS keys and Secrets Manager secrets are not Free Tier eligible beyond the
first months/limits; at portfolio scale this is a few USD per month. The
`.trivyignore` cost exemption for SSE-S3 on the public web bucket predates
this module; passing `kms_key_arn` intentionally upgrades those buckets to
SSE-KMS.
