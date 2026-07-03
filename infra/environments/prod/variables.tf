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
