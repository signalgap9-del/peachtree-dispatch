# Publish Readiness

## Public Access Model

- CloudFront serves the private S3 web origin over HTTPS.
- CloudFront viewer access is limited to `US` and `KR`.
- The portfolio web application is publicly readable from the allowed
  countries. Cognito JWT ownership protects authenticated user writes.
- Security response headers deny framing, prevent MIME sniffing, enforce HSTS,
  and use a strict referrer policy.
- Browser API traffic uses the same CloudFront distribution under `/api/*`.
  CloudFront adds a Terraform-generated origin verification secret, and the
  Spring API rejects direct API Gateway requests that do not contain it.
- Request throttling and Lambda reserved concurrency provide an additional
  low-cost abuse boundary without the fixed monthly AWS WAF charge.

## Release Gates

Before production promotion:

1. Web lint, design lint, build, API tests, Spring tests, container builds, and
   Terraform validation pass in CI.
2. Browser smoke tests cover Home, Map, Dashboard, Saved, Alerts, place detail,
   route search, route comparison, weather-layer toggle, and route saving.
3. The dev CloudFront URL is checked from an allowed country and a denied
   country.
4. API throttling returns `429` under a controlled burst test.
5. AWS Cost Explorer and CloudWatch logs are checked after 24 hours.

## Known Public-Portfolio Boundaries

- Cognito-backed personal accounts and DynamoDB saved places are enabled.
- Saved-route comparison state still uses browser local storage.
- High-resolution HRRR/MRMS processing is not scheduled until a measured
  one-shot run proves it remains within the monthly budget.
- Aurora PostgreSQL/PostGIS is code-ready but disabled by default. Enabling it
  requires an explicit Terraform variable and cost review.
- User-owned saved-place writes require a Cognito JWT and derive the immutable
  owner partition from its subject.
- Cognito Managed Login uses authorization-code flow with PKCE. The browser
  keeps short-lived ID/access tokens in session storage; no client secret is
  embedded in the web application.
- Authenticated saved-place APIs use the deployed on-demand DynamoDB table.
