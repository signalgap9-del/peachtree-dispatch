# AWS Project Rules

- Use `us-east-1` unless a service requirement says otherwise.
- Use `./scripts/aws.ps1` for AWS CLI commands on this machine.
- Use the `bootstrap` AWS profile until IAM Identity Center onboarding is complete.
- The `bootstrap` profile uses temporary browser-login credentials and currently represents the account root user.
- Do not use root credentials for routine project work after Identity Center onboarding.
- Never create, request, print, or commit long-lived AWS access keys.
- Prefer infrastructure as code for deployable resources.
- Tag deployable resources with `Project=awsresumeproject`, `ManagedBy=IaC`, and `Environment=dev`.
- Check estimated cost before creating resources that are not AWS Free Tier eligible.
- Require explicit user confirmation before creating resources with meaningful recurring cost.
- Keep destructive operations scoped to resources managed by this repository.
