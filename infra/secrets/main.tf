locals {
  name = "${var.project_name}-${var.environment}"

  # Placeholder secret payloads. Real values are NEVER stored in this
  # repository: after applying, operators set the real values with
  # `aws secretsmanager put-secret-value` (see README.md).
  secrets = {
    lemonsqueezy = {
      description = "Lemon Squeezy billing credentials (merchant of record). Read by the platform API billing package."
      value = {
        api_key        = "REPLACE_WITH_LEMONSQUEEZY_API_KEY"
        webhook_secret = "REPLACE_WITH_LEMONSQUEEZY_WEBHOOK_SECRET"
        store_id       = "REPLACE_WITH_LEMONSQUEEZY_STORE_ID"
        pro_variant_id = "REPLACE_WITH_LEMONSQUEEZY_PRO_VARIANT_ID"
      }
    }
    google-routes = {
      description = "Google Routes API key used for server-side route matrix and directions calls."
      value = {
        api_key = "REPLACE_WITH_GOOGLE_ROUTES_API_KEY"
      }
    }
    maptiler = {
      description = "MapTiler API key used for map tiles and geocoding."
      value = {
        api_key = "REPLACE_WITH_MAPTILER_API_KEY"
      }
    }
    database = {
      description = "Application database credentials for the SaaS relational store (Aurora PostgreSQL)."
      value = {
        username = "REPLACE_WITH_DB_USERNAME"
        password = "REPLACE_WITH_DB_PASSWORD"
        host     = "REPLACE_WITH_DB_HOST"
        port     = 5432
        database = "atmospath"
      }
    }
    auth = {
      description = "Authentication secrets: Cognito user pool identifiers and the API origin verification shared secret."
      value = {
        cognito_user_pool_id     = "REPLACE_WITH_COGNITO_USER_POOL_ID"
        cognito_client_id        = "REPLACE_WITH_COGNITO_CLIENT_ID"
        api_origin_verify_secret = "REPLACE_WITH_API_ORIGIN_VERIFY_SECRET"
      }
    }
  }
}

# ---------------------------------------------------------------------------
# Customer-managed KMS key: one key for encryption at rest across the
# platform (Secrets Manager secrets, DynamoDB SSE, S3 SSE-KMS).
# ---------------------------------------------------------------------------
resource "aws_kms_key" "platform" {
  description             = "Customer-managed encryption key for ${local.name} secrets and data stores"
  deletion_window_in_days = var.kms_deletion_window_days
  enable_key_rotation     = true
  multi_region            = false
}

resource "aws_kms_alias" "platform" {
  name          = "alias/${local.name}-platform"
  target_key_id = aws_kms_key.platform.key_id
}

# ---------------------------------------------------------------------------
# Application secrets (placeholders only).
# ---------------------------------------------------------------------------
resource "aws_secretsmanager_secret" "this" {
  for_each = local.secrets

  name                    = "${local.name}-${each.key}"
  description             = each.value.description
  kms_key_id              = aws_kms_key.platform.arn
  recovery_window_in_days = var.secret_recovery_window_days
}

resource "aws_secretsmanager_secret_version" "this" {
  for_each = local.secrets

  secret_id     = aws_secretsmanager_secret.this[each.key].id
  secret_string = jsonencode(each.value.value)
}

# ---------------------------------------------------------------------------
# Least-privilege IAM: read exactly these secrets, use exactly this key.
# ---------------------------------------------------------------------------
data "aws_iam_policy_document" "app_secrets" {
  statement {
    sid       = "ReadApplicationSecrets"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [for secret in aws_secretsmanager_secret.this : secret.arn]
  }

  statement {
    sid       = "UsePlatformKmsKey"
    actions   = ["kms:Decrypt", "kms:DescribeKey"]
    resources = [aws_kms_key.platform.arn]
  }
}

resource "aws_iam_policy" "app_secrets" {
  name_prefix = "${local.name}-app-secrets-"
  description = "Read ${local.name} application secrets and decrypt with the platform KMS key"
  policy      = data.aws_iam_policy_document.app_secrets.json
}

resource "aws_iam_role_policy_attachment" "app_secrets" {
  for_each = toset(var.app_role_names)

  role       = each.value
  policy_arn = aws_iam_policy.app_secrets.arn
}
