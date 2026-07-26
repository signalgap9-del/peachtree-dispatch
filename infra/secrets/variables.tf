variable "aws_region" {
  description = "AWS region for all secrets and KMS resources."
  type        = string
  default     = "us-east-1"
}

variable "project_name" {
  description = "Project name used to prefix every resource."
  type        = string
  default     = "peachtree-dispatch"
}

variable "environment" {
  description = "Deployment environment (dev, prod)."
  type        = string
  default     = "dev"
}

variable "app_role_names" {
  description = <<-EOT
    IAM role names (created by the application module, e.g.
    peachtree-dispatch-dev-api) that may read the application secrets and
    use the platform KMS key. Leave empty to create the policy without
    attaching it.
  EOT
  type        = list(string)
  default     = []
}

variable "kms_deletion_window_days" {
  description = "Waiting period before the KMS key can be deleted. Minimum 7."
  type        = number
  default     = 30
}

variable "secret_recovery_window_days" {
  description = "Secrets Manager recovery window. Set to 0 for immediate deletion in throwaway environments."
  type        = number
  default     = 30
}
