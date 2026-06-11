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
  default = 20
}

variable "api_throttling_rate_limit" {
  type    = number
  default = 10
}
