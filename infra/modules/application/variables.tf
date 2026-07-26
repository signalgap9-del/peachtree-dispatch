variable "project_name" {
  type    = string
  default = "peachtree-dispatch"
}

variable "environment" {
  type = string
}

variable "api_image_uri" {
  description = "Deprecated compatibility input for the Python risk engine image."
  type        = string
  default     = ""
}

variable "platform_api_image_uri" {
  description = "Immutable Spring Boot platform API image URI."
  type        = string
  default     = ""
}

variable "risk_engine_image_uri" {
  description = "Immutable Python risk engine and optimizer image URI."
  type        = string
  default     = ""
}

variable "weather_raster_image_uri" {
  description = "Optional immutable HRRR/MRMS raster worker image URI."
  type        = string
  default     = ""
}

variable "enable_hrrr_mrms_raster" {
  description = "Opt in to the scheduled HRRR/MRMS raster worker. Keep false for the low-cost default environment."
  type        = bool
  default     = false
}

variable "weather_raster_schedule_expression" {
  description = "EventBridge schedule for the optional HRRR/MRMS raster worker."
  type        = string
  default     = "rate(6 hours)"
}

variable "log_retention_days" {
  type    = number
  default = 14
}

variable "enable_deletion_protection" {
  type    = bool
  default = false
}

variable "api_throttling_burst_limit" {
  type    = number
  default = 3
}

variable "api_throttling_rate_limit" {
  type    = number
  default = 1
}

variable "allowed_country_codes" {
  description = "ISO 3166-1 alpha-2 countries allowed to access the public web distribution."
  type        = list(string)
  default     = ["US", "KR"]
}

variable "lambda_reserved_concurrency" {
  description = "Optional per-function reserved concurrency. Null keeps the account-wide unreserved pool available."
  type        = number
  default     = null
}

variable "google_oauth_client_id" {
  description = "Optional Google OAuth client ID for Cognito social sign-in."
  type        = string
  default     = ""
  sensitive   = true
}

variable "google_oauth_client_secret" {
  description = "Optional Google OAuth client secret for Cognito social sign-in."
  type        = string
  default     = ""
  sensitive   = true
}

variable "additional_auth_callback_urls" {
  description = "Additional Cognito OAuth callback URLs, such as localhost for dev smoke tests."
  type        = list(string)
  default     = []
}

variable "additional_auth_logout_urls" {
  description = "Additional Cognito OAuth logout URLs, such as localhost for dev smoke tests."
  type        = list(string)
  default     = []
}

variable "enable_relational_store" {
  description = "Provision the optional Aurora PostgreSQL Serverless v2 + PostGIS relational store."
  type        = bool
  default     = false
}

variable "use_aurora_express_configuration" {
  description = "Create Aurora through the Free Plan-compatible express configuration workflow."
  type        = bool
  default     = false
}

variable "relational_min_capacity" {
  description = "Minimum Aurora Serverless v2 ACUs. Zero enables auto-pause on supported engine versions."
  type        = number
  default     = 0
}

variable "relational_max_capacity" {
  description = "Hard Aurora Serverless v2 compute ceiling."
  type        = number
  default     = 1
}

variable "kms_key_arn" {
  description = <<-EOT
    Optional customer-managed KMS key ARN (from infra/secrets). When set,
    the DynamoDB table uses SSE-KMS with this key and the S3 buckets
    default to SSE-KMS; application roles also get decrypt permissions.
    Leave empty to keep the low-cost defaults (DynamoDB default
    encryption, SSE-S3).
  EOT
  type        = string
  default     = ""
}

variable "custom_domain_aliases" {
  description = "Custom domain aliases (CNAMEs) for the CloudFront distribution. Empty list keeps the default cloudfront.net certificate."
  type        = list(string)
  default     = []
}

variable "acm_certificate_arn" {
  description = "ARN of the ACM certificate (us-east-1) for custom domain TLS. Required when custom_domain_aliases is non-empty."
  type        = string
  default     = ""
}
