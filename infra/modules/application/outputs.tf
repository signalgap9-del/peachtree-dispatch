output "api_ecr_repository_url" {
  value = aws_ecr_repository.api.repository_url
}

output "api_url" {
  value = try(aws_apigatewayv2_api.api[0].api_endpoint, null)
}

output "web_bucket_name" {
  value = aws_s3_bucket.web.id
}

output "web_url" {
  value = "https://${aws_cloudfront_distribution.web.domain_name}"
}

output "cloudfront_distribution_id" {
  value = aws_cloudfront_distribution.web.id
}

output "dynamodb_table_name" {
  value = aws_dynamodb_table.operational.name
}
