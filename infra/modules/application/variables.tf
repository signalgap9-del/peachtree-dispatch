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
  description = "Optional per-function reserved concurrency. Null keeps the account-wide unreserved pool available."
  type        = number
  default     = null
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
