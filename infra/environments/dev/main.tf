module "domain" {
  count  = var.enable_custom_domain ? 1 : 0
  source = "../../domain"

  domain_name = "freightscaler.com"
}

module "application" {
  source = "../../modules/application"

  environment                = "dev"
  api_image_uri              = var.api_image_uri
  platform_api_image_uri     = var.platform_api_image_uri
  risk_engine_image_uri      = var.risk_engine_image_uri
  weather_raster_image_uri   = var.weather_raster_image_uri
  enable_hrrr_mrms_raster    = var.enable_hrrr_mrms_raster
  log_retention_days         = 14
  enable_deletion_protection = false
  enable_relational_store    = var.enable_relational_store
  google_oauth_client_id     = var.google_oauth_client_id
  google_oauth_client_secret = var.google_oauth_client_secret
  kms_key_arn                = var.kms_key_arn
  additional_auth_callback_urls = [
    "http://localhost:5173/",
    "http://127.0.0.1:5173/",
  ]
  additional_auth_logout_urls = [
    "http://localhost:5173/",
    "http://127.0.0.1:5173/",
  ]

  # Custom domain — active only when enable_custom_domain = true.
  custom_domain_aliases = var.enable_custom_domain ? module.domain[0].cloudfront_aliases : []
  acm_certificate_arn   = var.enable_custom_domain ? module.domain[0].certificate_arn : ""
}

module "monitoring" {
  source = "../../monitoring"

  environment         = "dev"
  alert_email         = var.alert_email
  api_url             = module.application.api_url != null ? module.application.api_url : ""
  api_gateway_id      = module.application.api_gateway_id != null ? module.application.api_gateway_id : ""
  dynamodb_table_name = module.application.dynamodb_table_name
  dlq_name            = module.application.optimization_dlq_name
  log_retention_days  = 14
}

output "api_ecr_repository_url" {
  value = module.application.api_ecr_repository_url
}

output "platform_api_ecr_repository_url" {
  value = module.application.platform_api_ecr_repository_url
}

output "weather_raster_ecr_repository_url" {
  value = module.application.weather_raster_ecr_repository_url
}

output "api_url" {
  value = module.application.api_url
}

output "web_bucket_name" {
  value = module.application.web_bucket_name
}

output "web_url" {
  value = module.application.web_url
}

output "cognito_client_id" {
  value = module.application.cognito_client_id
}

output "cognito_domain" {
  value = module.application.cognito_domain
}

output "google_auth_enabled" {
  value = module.application.google_auth_enabled
}

output "cloudfront_distribution_id" {
  value = module.application.cloudfront_distribution_id
}

output "dynamodb_table_name" {
  value = module.application.dynamodb_table_name
}

output "weather_snapshot_bucket_name" {
  value = module.application.weather_snapshot_bucket_name
}

output "relational_cluster_arn" {
  value = module.application.relational_cluster_arn
}

output "relational_secret_arn" {
  value     = module.application.relational_secret_arn
  sensitive = true
}

output "relational_endpoint" {
  value = module.application.relational_endpoint
}

output "relational_master_username" {
  value = module.application.relational_master_username
}

output "alarm_sns_topic_arn" {
  value = module.monitoring.sns_topic_arn
}

output "monitoring_dashboard_url" {
  value = module.monitoring.dashboard_url
}

output "domain_certificate_arn" {
  value = var.enable_custom_domain ? module.domain[0].certificate_arn : null
}

output "domain_validation_records" {
  value       = var.enable_custom_domain ? module.domain[0].certificate_domain_validation_options : null
  description = "DNS validation records to create in Cloudflare before the certificate is issued."
}

output "domain_validation_instructions" {
  value       = var.enable_custom_domain ? module.domain[0].validation_instructions : null
  description = "Human-readable Cloudflare DNS setup instructions."
}
