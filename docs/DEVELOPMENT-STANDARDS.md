# Development Standards (작업지시서)

This is the standing work instruction for ALL development on this repository.
Every coding task — feature, fix, refactor, review — must follow this document.
It is referenced from `AGENTS.md` and is mandatory, not advisory.

---

## 1. Required Reading (필독서)

These live in [`docs/required-reading/`](required-reading/) and define the quality
bar. Consult the relevant chapters before and during the work they apply to.

| Document | File | Apply it to |
|---|---|---|
| **Software Engineering at Google** (전25장) | `required-reading/SoftwareEngineeringAtGoogle.pdf` | Testing (Ch. 11-14), Code Review (Ch. 15-16), CI (Ch. 23), Documentation (Ch. 10), Large-scale Changes (Ch. 22), Style & conventions |
| **Chrome Team: First PR Complete Guide** (크롬팀 첫 PR 완전정복) | `required-reading/ChromeTeam_FirstPR_Guide.pdf` | Every PR: small focused changes, clear descriptions, reviewer-friendly commits, test evidence |

Core principles to internalize from these:
- **Tests are not optional.** New logic ships with tests. Review the testing chapters before writing testable code.
- **Code review is a quality gate, not a formality.** Every change should be reviewable in isolation.
- **Small, focused PRs.** One logical change per commit. No drive-by refactors bundled with features.
- **Documentation is part of the work**, not an afterthought.
- **Optimize for the reader of the code**, not the writer.

---

## 2. Mandatory Production-Grade Skills

The following skills (registered in `~/.codex/skills/`) are production-grade
engineering practices. **Always invoke the skill that matches the work type.**
When a task spans multiple types, use all matching skills.

| When the work is... | Use this skill |
|---|---|
| Planning a feature or breaking down a task | `planning-and-task-breakdown`, `spec-driven-development` |
| Designing an API, module boundary, or interface | `api-and-interface-design` |
| Building or modifying user-facing UI | `frontend-ui-engineering` |
| Implementing any logic, fix, or feature | `test-driven-development`, `incremental-implementation` |
| Grounding decisions in official docs/research | `source-driven-development` |
| Security-sensitive (auth, input, data, secrets) | `security-and-hardening` |
| Performance work or a perf regression | `performance-optimization` |
| Debugging a failure or unexpected behavior | `debugging-and-error-recovery` |
| Adding logging, metrics, or tracing | `observability-and-instrumentation` |
| Writing docs or recording a decision | `documentation-and-adrs` |
| Committing, branching, or versioning | `git-workflow-and-versioning` |
| Setting up or changing CI/CD | `ci-cd-and-automation` |
| Preparing a production launch | `shipping-and-launch` |
| Removing or migrating a feature | `deprecation-and-migration` |
| Reviewing code before merge | `code-review-and-quality`, `doubt-driven-development` |

### Non-negotiable rules

1. **No production code without tests.** If you write logic, write the test in the
   same change. Run the relevant suite before declaring done.
2. **No UI from intuition.** Follow `DESIGN.md` and `frontend-ui-engineering`.
   Verify at desktop + mobile widths before finishing.
3. **No security-sensitive change without `security-and-hardening`.** Auth, input
   handling, secrets, and data access all require it.
4. **No merge without review.** Apply `code-review-and-quality` to your own diff
   before committing. Prefer `doubt-driven-development` for high-risk decisions.
5. **Record decisions.** Non-obvious architectural choices get an ADR
   (`documentation-and-adrs`), numbered in `docs/adr/`.
6. **Small commits.** One logical change per commit, per the Chrome PR guide.

---

## 3. Definition of Done

A task is not done until ALL of these are true:

- [ ] Code compiles / type-checks clean (`npx tsc -b` for web, `pytest` for Python)
- [ ] New/changed logic has tests, and the relevant suite passes
- [ ] UI changes verified visually at 375px and 1440px
- [ ] No new accessibility regressions (contrast, keyboard, aria)
- [ ] Lint passes (`npm run lint --prefix web`)
- [ ] Bundle budget still passes if frontend changed (`npm run perf:budget --prefix web`)
- [ ] Non-obvious decisions recorded as an ADR
- [ ] README / relevant docs updated if user-facing behavior changed
- [ ] Change is a small, focused, reviewable commit

---

## 4. Workflow (per task)

1. **Understand** — read the relevant code and `docs/` before writing anything.
2. **Plan** — for non-trivial work, break it down (`planning-and-task-breakdown`).
3. **Implement** — TDD where practical, incrementally, with the matching skills.
4. **Verify** — run tests, lint, build, and visual checks (Definition of Done).
5. **Review** — self-review the diff against `code-review-and-quality`.
6. **Document** — update docs / add ADRs as needed.
7. **Commit** — small, focused, well-described commits.

---

## 5. Quality Bar

This is a product intended for real paying users, not a demo. Every change should
meet the standard of code you would put in front of a customer. When in doubt,
apply `doubt-driven-development` and ask: "Would this hold up in a production
incident and a code review by a senior engineer?"
