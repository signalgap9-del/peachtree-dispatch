module "application" {
  source = "../../modules/application"

  environment                = "prod"
  api_image_uri              = var.api_image_uri
  platform_api_image_uri     = var.platform_api_image_uri
  risk_engine_image_uri      = var.risk_engine_image_uri
  log_retention_days         = 30
  enable_deletion_protection = true
  enable_relational_store    = var.enable_relational_store
}

output "api_ecr_repository_url" {
  value = module.application.api_ecr_repository_url
}

output "platform_api_ecr_repository_url" {
  value = module.application.platform_api_ecr_repository_url
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

output "cloudfront_distribution_id" {
  value = module.application.cloudfront_distribution_id
}

output "cognito_client_id" {
  value = module.application.cognito_client_id
}

output "cognito_domain" {
  value = module.application.cognito_domain
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
