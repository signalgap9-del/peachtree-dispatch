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
