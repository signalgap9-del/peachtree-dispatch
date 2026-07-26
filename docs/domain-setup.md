# Domain Setup: freightscaler.com

**STATUS: CONFIG READY — NOT APPLIED YET.**

The Terraform configuration in `infra/domain/` and the application module
changes are written and syntactically valid, but `terraform apply` has NOT
been run. No Cloudflare DNS records have been created. No ACM certificate
has been issued. Follow the steps below when ready to go live.

---

## Architecture

```
freightscaler.com ──CNAME──> dXXXXXXXXXX.cloudfront.net
www.freightscaler.com ──CNAME──> dXXXXXXXXXX.cloudfront.net
                                      │
                                CloudFront (us-east-1)
                                ACM cert: freightscaler.com + www
                                      │
                              ┌───────┴───────┐
                              S3 (web)    API Gateway (/api/*)
```

DNS is hosted on **Cloudflare**. The ACM certificate is issued by AWS in
**us-east-1** (required by CloudFront). SSL termination happens at
CloudFront; Cloudflare passes through without its own proxy SSL.

---

## Step 1: Create the ACM Certificate

```powershell
cd infra/environments/dev

# Preview what will be created
./scripts/terraform.ps1 plan -var="enable_custom_domain=true"

# Create the certificate (does NOT touch CloudFront until Step 3)
./scripts/terraform.ps1 apply -var="enable_custom_domain=true"
```

This creates `aws_acm_certificate.web` in us-east-1. The certificate
will be in `PENDING_VALIDATION` status until the DNS records are added.

Retrieve the validation records:

```powershell
./scripts/terraform.ps1 output domain_validation_instructions
./scripts/terraform.ps1 output domain_validation_records
```

---

## Step 2: Add DNS Validation Records in Cloudflare

In the Cloudflare dashboard for `freightscaler.com`, go to
**DNS > Records** and add the validation CNAME records from the
Terraform output. They will look like this (actual values come from
the `terraform output` above):

| Type | Name | Content | Proxy |
|------|------|---------|-------|
| CNAME | `_XXXXXXXX.freightscaler.com` | `_YYYYYYYY.acm-validations.aws` | DNS-only (grey cloud) |
| CNAME | `_XXXXXXXX.www.freightscaler.com` | `_YYYYYYYY.acm-validations.aws` | DNS-only (grey cloud) |

**Important:** Set these validation records to **DNS-only (grey cloud)**,
not proxied. Cloudflare's proxy would intercept the validation request
and ACM would fail to verify.

After adding the records, the certificate typically validates within
5 minutes. Check status:

```powershell
./scripts/aws.ps1 acm describe-certificate `
  --certificate-arn "<cert-arn-from-output>" `
  --query 'Certificate.Status'
# Expected: "ISSUED"
```

---

## Step 3: Add Traffic CNAME Records in Cloudflare

Once the certificate is `ISSUED`, add the traffic-routing records:

| Type | Name | Content | Proxy |
|------|------|---------|-------|
| CNAME | `freightscaler.com` | `dXXXXXXXXXX.cloudfront.net` | DNS-only (grey cloud) |
| CNAME | `www` | `dXXXXXXXXXX.cloudfront.net` | DNS-only (grey cloud) |

Replace `dXXXXXXXXXX.cloudfront.net` with the actual CloudFront domain
name from `terraform output web_url` (strip the `https://` prefix).

### Why DNS-only (grey cloud) instead of proxied (orange cloud)?

**Recommendation: DNS-only (grey cloud).**

| | DNS-only (grey cloud) | Proxied (orange cloud) |
|---|---|---|
| SSL | CloudFront handles SSL end-to-end | Cloudflare terminates SSL, then re-encrypts to CloudFront (double SSL) |
| Certificates | One cert (ACM) to manage | Two certs (ACM + Cloudflare) |
| Caching | CloudFront edge caching only | Cloudflare + CloudFront (cache coordination complexity) |
| DDoS | CloudFront Shield Standard | Cloudflare DDoS protection + CloudFront Shield |
| WAF | CloudFront + AWS WAF | Cloudflare WAF + AWS WAF (duplicate rules) |
| Complexity | Lower | Higher (two CDN layers, header conflicts) |

For a single-origin setup like FreightScaler, running two CDN layers
adds complexity without meaningful benefit. CloudFront already provides
edge caching, DDoS protection (Shield Standard), and AWS WAF
integration. Use DNS-only so Cloudflare acts purely as a DNS provider.

If you later want Cloudflare's DDoS or bot-management features, you can
switch to proxied, but you will need to set Cloudflare SSL mode to
"Full (strict)" and ensure the CloudFront certificate is trusted.

---

## Step 4: Verify

```bash
# Check the custom domain serves the web app
curl -sI https://freightscaler.com | head -5
# Expected: HTTP/2 200, server: Amazon CloudFront

# Check www redirect
curl -sI https://www.freightscaler.com | head -5
# Expected: HTTP/2 200 (both serve the same content)

# Verify the TLS certificate
echo | openssl s_client -connect freightscaler.com:443 -servername freightscaler.com 2>/dev/null \
  | openssl x509 -noout -subject -issuer -dates
# Expected: subject CN=freightscaler.com, issuer=Amazon RSA 2048 M01

# Check API health through the custom domain
curl -s https://freightscaler.com/api/health
# Expected: {"status":"healthy",...}
```

---

## Rollback

If anything goes wrong:

1. **DNS:** Delete the traffic CNAME records in Cloudflare. The site
   reverts to the `cloudfront.net` URL immediately (TTL permitting).
2. **Terraform:** Set `enable_custom_domain = false` and re-apply.
   This removes the aliases from CloudFront and destroys the ACM cert.
3. **Certificate:** ACM certs in `PENDING_VALIDATION` can be deleted
   safely. Issued certs should be deleted only after CloudFront no
   longer references them.

---

## Terraform Resources Created

| Resource | Module | Purpose |
|----------|--------|---------|
| `aws_acm_certificate.web` | `infra/domain` | TLS cert for freightscaler.com + www |
| `aws_cloudfront_distribution.web` (modified) | `infra/modules/application` | Adds `aliases` + ACM cert to `viewer_certificate` |

No Route 53 resources are created (DNS is on Cloudflare).
No Cloudflare resources are managed by Terraform (manual DNS steps above).
