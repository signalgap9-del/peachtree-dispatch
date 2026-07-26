# FreightScaler — SaaS Launch Roadmap

Master plan for turning the FreightScaler portfolio project into a real, sellable
SaaS. Resume from here. Last updated: 2026-07-26.

---

## Current State (what's already built)

- Full-stack app: React 19 + Spring Boot 3.5 + FastAPI risk engine
- Weather-aware route planning with risk-aware smart default (risk threshold logic)
- LLM integration: NL2Opt, RAG (pgvector), proactive risk intelligence
- Production data stack: PostgreSQL/TimescaleDB, Redis, Kafka, Docker
- SaaS scaffolding: entitlements, quotas, plans (FREE/PRO), saved routes/places
- Landing page, onboarding, legal pages, settings, PWA, accessibility (0 axe violations)
- 116 Python tests, frontend E2E, bundle budgets passing
- Rebranded to FreightScaler (general purpose: car/van/truck; truck/hazmat = Pro)
- Development standards + required reading in `docs/DEVELOPMENT-STANDARDS.md`

---

## 0. Repository Strategy (public portfolio + private production)

**Decision:** split into a public portfolio repo and a private production repo.

**Secret scan status (2026-07-26):** CLEAN. No real secrets in working tree or git
history (5 false positives: local dev password defaults + test fixture tokens).

### Execution steps

1. **Create a PRIVATE production repo** (e.g., `freightscaler`):
   - This is the living product repo. Billing, deployment configs, real env handling go here.
   - Push the current codebase as the starting point.
2. **Keep `peachtree-dispatch` PUBLIC as the portfolio:**
   - It is verified secret-free. It showcases the engineering for employers.
   - Treat it as a portfolio snapshot. Optionally sync sanitized updates periodically.
   - Add a top-of-README note: "Portfolio version. Production runs on a private repo."
3. **Going forward:** new commercial features (billing, production deploy) land in the
   private repo. The public repo stays a clean showcase.

### Why this is safe
The code is not the moat — the deployment, data pipeline, brand, and execution are.
Public code without credentials exposes the engineering (good for portfolio) without
giving away the business.

---

## 1. Secret Hygiene (ongoing)

- Never commit secrets. All credentials via environment variables.
- `.gitignore` must cover `.env`, `*.pem`, `*.key`, local config.
- Enable **GitHub Secret Scanning** + **push protection** on both repos
  (Settings → Code security and analysis).
- Before any push of billing code: re-run a secret scan (`tmp/secret_scan.py` pattern).
- Local dev password defaults (`replicator_password`, `atmospath-local`) are fine for
  local Docker but MUST be overridden in production via env vars.

---

## 2. Phase B — Monetization

**Model:** Lemon Squeezy (Merchant of Record — handles global tax/VAT; works for
Korean sellers, unlike Stripe). **Credit/count-based plans, NOT usage-based billing.**

### Plans
```
Free:  e.g. 10 route plans/day, 5 AI explanations/day
Pro:   e.g. 100/day or unlimited, + truck/hazmat/fleet features  ($29/vehicle/mo)
```
The existing `EntitlementService` + daily counters already enforce counts. Lemon
Squeezy only signals "is this user Pro?" via webhook; our code handles the limits.

### Steps
| Step | Task | Notes |
|---|---|---|
| B1 | `LemonSqueezyClient` (Spring) | API: create checkout, get subscription, verify. Reference: `lmsqueezy/nextjs-billing` (official, 739★) — port patterns to Spring Boot. |
| B2 | Webhook endpoint + HMAC-SHA256 signature verification + idempotency | `POST /api/v1/billing/webhook`. Must verify signature exactly. Handle duplicate delivery. |
| B3 | Subscription sync → `subscription` table → `EntitlementService` | Map LS states (active/paused/past_due/cancelled) → plan tier → credit limits. |
| B4 | Plan enforcement interceptor | Guard Pro-only endpoints/features. |
| B5 | Frontend: pricing → checkout → redirect; account page (plan, renewal, manage) | Upgrade button → backend → LS checkout URL → redirect back. |
| B6 | E2E test in Lemon Squeezy test mode | Verify full subscribe/cancel/upgrade flow. |

### Needed from user (to start B1)
- Lemon Squeezy **Store ID**
- **API Key**
- **Webhook Secret**
- **Pro Variant ID** (and Free if applicable)

---

## 3. Anti-Abuse / Security (part of Phase B)

LLM calls cost money, so abuse prevention is financial protection.

| Layer | Tool | Cost |
|---|---|---|
| Bot signups/forms | **Cloudflare Turnstile** | free |
| General bots | **Cloudflare Bot Fight Mode** | free |
| Edge rate limiting | **Cloudflare Rate Limiting** | free tier |
| DDoS | **AWS Shield Standard** | free, automatic on CloudFront/API GW |
| Per-endpoint limits | **API Gateway throttling** | free |
| LLM-specific | **App-level** (build) | — |

### App-level to build
1. Auth-gate LLM endpoints (block anonymous LLM calls).
2. Stricter per-user + per-IP rate limits on LLM endpoints.
3. **Cost circuit breaker** (critical): if daily LLM cost > $X, degrade the feature.
   This is what actually prevents a money blowup.
4. Turnstile token verification on signup/login.

**Defense in depth:** Cloudflare (edge, free) → AWS Shield (auto) → API GW throttling
→ app-level budgets + circuit breaker.

---

## 4. Phase C — Launch

| Step | Task | Notes |
|---|---|---|
| C1 | Connect freightscaler.com → AWS | CloudFront CNAME + Cloudflare DNS (CNAME, proxied). |
| C2 | Public webhook URL + HTTPS | API Gateway endpoint for Lemon Squeezy webhooks. |
| C3 | Demo data seed | Realistic routes/alerts so the product feels alive on first visit. |
| C4 | Support channel | Simple feedback form or support email. |
| C5 | Final polish + monitoring | Error tracking, uptime check, last a11y/perf pass. |

---

## 5. Phase D — Retention (later)

- Email alert digests (saved route risk changes)
- Mobile polish / PWA install prompts
- Privacy-respecting analytics
- Usage insights for Pro users

---

## Effort Summary

| Phase | Sessions (sub-agent) | Realistic time |
|---|---|---|
| 0. Repo split + secret hygiene | 1 | half day |
| B. Monetization + anti-abuse | 5-6 | 1-2 days |
| C. Launch | 4 | 1 day |
| D. Retention | ongoing | — |

**Total to a sellable SaaS: ~2-3 focused days with sub-agents.**

---

## Decisions Log

- **Lemon Squeezy over Stripe**: Stripe is hard for Korean individuals; LS is Merchant of Record (handles tax).
- **Credit/count plans over usage billing**: simpler, predictable; existing quota system already does it.
- **No Meteroid/Lago**: they change the model to "you are the merchant" (you handle tax). Overkill + tax burden. Revisit only if usage metering gets complex later.
- **Cloudflare-first security**: domain is on Cloudflare; free Turnstile/Bot Fight/rate limiting + AWS Shield Standard (auto) cover most. App-level cost circuit breaker is the critical financial guard.
- **Public portfolio + private production split**: code is not the moat; public code (secret-free) showcases engineering, private repo holds the live product.
