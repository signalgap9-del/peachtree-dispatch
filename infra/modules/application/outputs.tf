output "api_ecr_repository_url" {
  value = aws_ecr_repository.api.repository_url
}

output "platform_api_ecr_repository_url" {
  value = aws_ecr_repository.platform_api.repository_url
}

output "weather_raster_ecr_repository_url" {
  value = aws_ecr_repository.weather_raster.repository_url
}

output "api_url" {
  value = local.deploy_app ? "https://${aws_cloudfront_distribution.web.domain_name}/api" : null
}

output "web_bucket_name" {
  value = aws_s3_bucket.web.id
}

output "web_url" {
  value = "https://${aws_cloudfront_distribution.web.domain_name}"
}

output "cognito_client_id" {
  value = aws_cognito_user_pool_client.web.id
}

output "cognito_domain" {
  value = "${aws_cognito_user_pool_domain.web.domain}.auth.us-east-1.amazoncognito.com"
}

output "google_auth_enabled" {
  value = nonsensitive(var.google_oauth_client_id != "" && var.google_oauth_client_secret != "")
}

output "cloudfront_distribution_id" {
  value = aws_cloudfront_distribution.web.id
}

output "dynamodb_table_name" {
  value = aws_dynamodb_table.operational.name
}

output "weather_snapshot_bucket_name" {
  value = aws_s3_bucket.weather.id
}

output "relational_cluster_arn" {
  value = var.enable_relational_store ? local.relational_cluster_arn : null
}

output "relational_secret_arn" {
  value     = var.enable_relational_store ? local.relational_secret_arn : null
  sensitive = true
}

output "relational_endpoint" {
  value = !var.enable_relational_store ? null : (
    var.use_aurora_express_configuration
    ? data.aws_rds_cluster.relational_express[0].endpoint
    : aws_rds_cluster.relational[0].endpoint
  )
}

output "relational_master_username" {
  value = !var.enable_relational_store ? null : (
    var.use_aurora_express_configuration
    ? data.aws_rds_cluster.relational_express[0].master_username
    : aws_rds_cluster.relational[0].master_username
  )
}

output "api_gateway_id" {
  value = local.deploy_app ? aws_apigatewayv2_api.api[0].id : null
}

output "optimization_dlq_name" {
  value = aws_sqs_queue.optimization_dlq.name
}
