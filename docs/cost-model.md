# Cost Model

## Account Context

The account has initial AWS credits, but the architecture should remain affordable after credits expire. Credits are treated as room for controlled experiments, not as permission to leave expensive resources running.

## Steady-State Target

Target: **under $10/month** at assumed portfolio traffic.

| Service | Usage Model | Expected Portfolio Cost |
| --- | --- | --- |
| Lambda | Request and duration based; scales to zero | Usually within free usage or cents |
| API Gateway HTTP API | Per request | Cents at portfolio traffic |
| DynamoDB | Provisioned free-tier capacity initially | Usually within free usage |
| SQS and EventBridge | Per request | Cents at portfolio traffic |
| Step Functions Express | Per request and duration | Cents at portfolio traffic |
| S3 and CloudFront | Small static site | Cents |
| CloudWatch | Logs, custom metrics, dashboards | Main cost to watch; use short retention |
| KMS | Customer-managed keys | Small fixed monthly cost per key |
| ECS Fargate batch | Only when manually invoked | Controlled experiment cost |

## Services Deliberately Avoided Initially

| Service | Reason |
| --- | --- |
| EKS | Control-plane cost and operational scope are disproportionate to MVP value |
| NAT Gateway | Significant hourly and data-processing cost |
| Always-on ECS Fargate service | Pays while idle and normally requires additional networking/load-balancing resources |
| Application Load Balancer | Hourly cost is excessive for portfolio traffic |
| Always-on RDS/Aurora | Idle compute cost and operational complexity |

## Credit Strategy

- Keep the persistent development environment inexpensive.
- Use credits for short, documented experiments such as an ECS Fargate batch run, load test, or recovery exercise.
- Destroy experimental resources immediately after collecting evidence.
- Record the experiment cost and architectural conclusion in an ADR or postmortem.

## Guardrails

- Monthly budget currently set to $10.
- Add alerts at 50%, 80%, and 100% before deploying the application.
- Require a cost-impact section in infrastructure pull requests.
- Set CloudWatch log retention to 14 days in `dev`.
- Avoid high-cardinality custom metrics.
- Prefer one regional environment until the portfolio requires more.
