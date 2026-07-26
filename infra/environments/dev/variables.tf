variable "api_image_uri" {
  description = "Immutable ECR image URI for the API and optimizer."
  type        = string
  default     = ""
}

variable "platform_api_image_uri" {
  type    = string
  default = ""
}

variable "risk_engine_image_uri" {
  type    = string
  default = ""
}

variable "weather_raster_image_uri" {
  type    = string
  default = ""
}

variable "enable_hrrr_mrms_raster" {
  description = "Opt-in switch for the scheduled HRRR/MRMS raster worker."
  type        = bool
  default     = false
}

variable "enable_relational_store" {
  description = "Explicit cost-bearing opt-in for Aurora PostgreSQL Serverless v2."
  type        = bool
  default     = false
}

variable "google_oauth_client_id" {
  type      = string
  default   = ""
  sensitive = true
}

variable "google_oauth_client_secret" {
  type      = string
  default   = ""
  sensitive = true
}

variable "alert_email" {
  description = "Email address for operational alarm notifications."
  type        = string
  default     = ""
}

variable "kms_key_arn" {
  description = "Customer-managed KMS key ARN from infra/secrets. Enables SSE-KMS on DynamoDB and S3 when set."
  type        = string
  default     = ""
}

variable "enable_custom_domain" {
  description = "Provision the ACM certificate and attach freightscaler.com to CloudFront. CONFIG READY — set to true only when ready to apply."
  type        = bool
  default     = false
}
