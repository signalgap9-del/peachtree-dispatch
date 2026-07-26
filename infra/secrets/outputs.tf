output "kms_key_arn" {
  description = "ARN of the customer-managed platform KMS key. Feed this into the application module's kms_key_arn variable to enable SSE-KMS."
  value       = aws_kms_key.platform.arn
}

output "kms_key_alias" {
  description = "Alias of the platform KMS key."
  value       = aws_kms_alias.platform.name
}

output "secret_arns" {
  description = "ARNs of the application secrets, keyed by logical name."
  value       = { for key, secret in aws_secretsmanager_secret.this : key => secret.arn }
}

output "app_secrets_policy_arn" {
  description = "IAM policy ARN granting read access to the secrets and use of the KMS key."
  value       = aws_iam_policy.app_secrets.arn
}
