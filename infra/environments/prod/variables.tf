variable "api_image_uri" {
  description = "Immutable ECR image URI promoted from dev."
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

variable "enable_relational_store" {
  description = "Explicit cost-bearing opt-in for Aurora PostgreSQL Serverless v2."
  type        = bool
  default     = false
}
