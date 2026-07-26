# DNS validation records to create in Cloudflare.
# Each entry maps to a CNAME record that proves domain ownership to ACM.

output "certificate_arn" {
  description = "ARN of the issued ACM certificate. Pass this to the application module's acm_certificate_arn variable."
  value       = aws_acm_certificate.web.arn
}

output "certificate_domain_validation_options" {
  description = "DNS validation records to create in Cloudflare. Create one CNAME record per entry."
  value = {
    for option in aws_acm_certificate.web.domain_validation_options :
    option.domain_name => {
      name  = option.resource_record_name
      type  = option.resource_record_type
      value = option.resource_record_value
    }
  }
}

output "validation_instructions" {
  description = "Human-readable summary of the Cloudflare DNS records to create."
  value       = <<-EOT
    Create the following CNAME records in Cloudflare for ${var.domain_name}:

    %{for option in aws_acm_certificate.web.domain_validation_options~}
    Domain: ${option.domain_name}
      Type:  ${option.resource_record_type}
      Name:  ${option.resource_record_name}
      Value: ${option.resource_record_value}
      Proxy: DNS-only (grey cloud)

    %{endfor~}
    After creating these records, the certificate will validate automatically
    (usually within 5 minutes). Then update the CloudFront distribution with
    the certificate ARN and domain aliases.
  EOT
}

output "cloudfront_aliases" {
  description = "Domain aliases to set on the CloudFront distribution."
  value       = [var.domain_name, "www.${var.domain_name}"]
}
