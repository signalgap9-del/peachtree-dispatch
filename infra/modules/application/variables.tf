variable "project_name" {
  type    = string
  default = "peachtree-dispatch"
}

variable "environment" {
  type = string
}

variable "api_image_uri" {
  description = "Immutable ECR image URI including digest or tag. Empty creates the platform without Lambda."
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
