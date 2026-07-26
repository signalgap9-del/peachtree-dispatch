# ADR 0019: Billing UI — Hosted Checkout, Portal, and Graceful Degradation

## Status

Accepted

## Context

FreightScaler monetizes through Free / Pro / Team tiers. The billing backend
(Lemon Squeezy integration in the platform API) is being built in parallel with
the frontend against this contract:

```
POST /api/v1/billing/checkout    { variantId? } -> { checkoutUrl }
GET  /api/v1/billing/subscription -> { plan, status, currentPeriodEnd, cancelAtPeriodEnd, manageUrl? }
POST /api/v1/billing/portal      -> { portalUrl }
```

The web app must ship before those endpoints exist everywhere, must never
break the rest of the product while billing is absent, and must not handle
payment data itself.

## Decision

- **Hosted checkout only.** The pricing page calls `POST /billing/checkout`
  and redirects the browser to the returned `checkoutUrl` (Lemon Squeezy
  hosted page). The frontend never sees card data; the backend owns the
  variant, pricing, and webhook reconciliation.
- **Server-proxied base URL.** Billing calls go through the shared
  `api.request()` helper with `/billing/*` paths, so they inherit the
  `VITE_API_URL` base (which already includes `/api/v1`), bearer-token auth,
  timeout, and error-envelope handling. Checkout/portal POSTs are sent with
  `retries: 0` so a transient failure can never create duplicate checkouts.
- **Graceful degradation while billing rolls out.** A 404/503 from a billing
  endpoint is treated as "billing not live yet":
  - Pricing page probes `GET /billing/subscription` for signed-in users and
    shows a disabled "Checkout coming soon" state instead of a dead button.
  - Account page falls back to the existing `/me/account` summary and shows a
    "self-serve billing opens soon" panel.
  - The header plan probe fails silently; the Pro badge simply stays hidden.
  These probes use a `quiet` request option so expected 404s do not pollute
  client-issue telemetry.
- **Auth-aware upgrade flow.** Anonymous users get an inline "Sign in to
  upgrade" state (401/403 from checkout also lands here) instead of a broken
  redirect.
- **One upgrade prompt per surface.** Hitting a free-tier limit
  (`QUOTA_EXCEEDED` on directions, exceeded meters on Usage) renders a single
  dismissible `UpgradePrompt` banner linking to pricing. It never blocks the
  surrounding UI and never stacks.
- **Subscription state in the app shell.** `AppShell` fetches the
  subscription once per signed-in session and passes the plan code to the
  header for the subtle "Pro" nav badge; the Account page fetches its own
  copy so it stays correct after checkout/portal round trips.

## Consequences

- The frontend can ship and be tested end-to-end (Playwright mocks the three
  endpoints) before the backend lands; no feature flags are needed.
- Renewal dates, status, and cancellation are display-only; all mutation
  happens in the Lemon Squeezy customer portal via `manageUrl`/`portalUrl`.
- If the contract changes, only `api.ts`, `types.ts`, and the two billing
  surfaces change; the shared `UpgradePrompt` and nav badge are insulated.
