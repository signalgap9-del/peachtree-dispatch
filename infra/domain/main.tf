# FreightScaler custom domain: ACM certificate for CloudFront.
#
# This module creates an ACM certificate in us-east-1 (required by
# CloudFront) for freightscaler.com and www.freightscaler.com, using
# DNS validation. Because DNS is hosted on Cloudflare (not Route 53),
# the validation records must be created manually in the Cloudflare
# dashboard. The outputs below provide the exact record values.
#
# STATUS: CONFIG READY — NOT APPLIED.
# Do NOT run terraform apply until the deployment plan is approved.

locals {
  domain_name = var.domain_name
  # Subject alternative names: apex + www
  subject_alternative_names = [
    "www.${var.domain_name}",
  ]
}

# ACM certificate — must be in us-east-1 for CloudFront.
resource "aws_acm_certificate" "web" {
  domain_name               = local.domain_name
  subject_alternative_names = local.subject_alternative_names
  validation_method         = "DNS"

  lifecycle {
    create_before_destroy = true
  }

  tags = {
    Name = "${local.domain_name}-cloudfront"
  }
}

# Since DNS is on Cloudflare, we cannot auto-create validation records.
# Instead, we expose them as outputs for manual creation.
# See docs/domain-setup.md for the step-by-step procedure.
