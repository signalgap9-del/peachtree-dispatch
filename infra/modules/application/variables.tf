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
  description = "Small per-function concurrency ceiling to protect the portfolio account from runaway cost."
  type        = number
  default     = 2
}
