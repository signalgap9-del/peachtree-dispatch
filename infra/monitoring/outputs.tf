output "sns_topic_arn" {
  description = "ARN of the SNS topic used for alarm notifications."
  value       = aws_sns_topic.alarms.arn
}

output "dashboard_url" {
  description = "URL of the CloudWatch monitoring dashboard."
  value       = "https://us-east-1.console.aws.amazon.com/cloudwatch/home?region=us-east-1#dashboards:name=${aws_cloudwatch_dashboard.monitoring.dashboard_name}"
}

output "canary_function_name" {
  description = "Name of the synthetic canary Lambda function (empty if not deployed)."
  value       = local.deploy_canary ? aws_lambda_function.canary[0].function_name : ""
}
