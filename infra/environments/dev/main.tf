data "aws_caller_identity" "current" {}

output "aws_account_id" {
  description = "Account targeted by the development environment."
  value       = data.aws_caller_identity.current.account_id
}
