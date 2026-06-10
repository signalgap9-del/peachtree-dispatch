# ADR 0002: Use Lambda for Request Workloads and Fargate for On-Demand Batch

## Status

Accepted

## Context

The platform must demonstrate credible AWS and DevOps decisions while serving low, bursty portfolio traffic. A container platform is useful portfolio evidence, but an always-on container service and load balancer would spend money while idle. Lambda has operational constraints, but those constraints do not conflict with the short request and event-processing workloads in the MVP.

## Options Considered

| Option | Advantages | Disadvantages | Decision |
| --- | --- | --- | --- |
| Lambda-only | Lowest idle cost, automatic scaling, native event integrations | Limited container-platform evidence, execution limits | Use for MVP request/event paths |
| Always-on ECS Fargate API | Strong container and deployment signal | Pays while idle; load balancing/network costs | Reject for persistent MVP |
| EC2-hosted API | Cheap on a small instance and demonstrates server management | Patching, availability, and manual scaling distract from product | Reject |
| EKS | Strong Kubernetes signal | High fixed cost and excessive scope | Reject |
| Hybrid Lambda + on-demand Fargate task | Low steady cost plus real container/ECS evidence | Two compute models to operate | Accept |

## Decision

- Use Python Lambda functions behind API Gateway HTTP API for synchronous commands and queries.
- Use Lambda consumers for short asynchronous event processing from SQS.
- Use an on-demand ECS Fargate task for a later CSV report or replay workload that can run without inbound networking.
- Do not create an always-on ECS service, load balancer, NAT Gateway, or EKS cluster in the persistent environment.

## Lambda Boundaries

Lambda is appropriate only when all are true:

- Execution completes within a few minutes.
- Work is stateless between invocations.
- Burst scaling is valuable.
- The operation can be retried safely.

Move work to Fargate when it requires long execution, custom system dependencies, large memory/CPU allocation, or explicit container lifecycle control.

## Consequences

- The persistent environment remains inexpensive.
- The project still demonstrates Docker image build, ECR, ECS task definitions, IAM task roles, and controlled task execution.
- Architecture documentation must clearly explain which workload belongs to each compute model.
