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

# Frontend Design Rules

- Read and follow the root `DESIGN.md` before frontend design or implementation work. Run `npm run design:lint --prefix web` after changing it.
- Before implementing a substantial feature from scratch, search public GitHub repositories and established products for relevant implementations and interaction patterns.
- Prefer adapting a maintained, license-compatible public implementation over inventing an equivalent implementation from scratch.
- Before importing public code, verify its license, maintenance state, dependencies, security implications, and fit with this repository. Do not import code with an absent, unclear, or incompatible license.
- Record evaluated repositories, licenses, selected patterns, and what was adapted in the relevant document under `docs/design/` or `docs/architecture/`.
- Preserve required copyright and attribution notices when adapting licensed code. Never copy secrets, API tokens, trademarks, proprietary assets, or vulnerable dependencies from reference repositories.
- Do not implement or substantially restyle frontend UI from intuition alone.
- Before major frontend work, collect 2-5 concrete references from real products or websites. Record their URLs, screenshots, and the specific interaction or visual treatment being studied in `docs/design/`.
- Use Open Design, Figma, or the image-generation tool to create at least one visual mockup before editing production UI code for a new screen or major redesign.
- Present the proposed visual direction or mockup to the user and obtain approval before major frontend implementation, unless the user explicitly asks to skip review.
- Treat generated images as design references or original production assets, not as a substitute for accessible, responsive HTML and CSS.
- Screenshots and third-party website assets may be used as private design references only. Do not commit or ship copyrighted assets without permission.
- Preserve existing product behavior while translating the approved visual direction into code.
- After implementation, verify the result visually in the browser at desktop and mobile widths and compare it against the approved reference or mockup.
- Keep durable visual direction, reference notes, mockups, and design decisions in `docs/design/`.
