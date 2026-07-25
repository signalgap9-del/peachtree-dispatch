# Landing Page Design Notes

Scope: marketing landing page at `/` (`web/src/LandingPage.tsx`); the product
app moved to `/app/*` with legacy paths redirecting.

## Direction

Open with the product, not a slogan. The first viewport shows the real route
comparison UI (screenshot of the live map with the Seattle to Miami I-80 vs
I-40 decision) inside an app frame, with live-signal chips floating over it.
All numbers on the page come from the same Seattle to Miami scenario the app
uses: 3 alternatives, risk 58 to 15, 628 minutes of climate delay avoided.

Tokens follow `DESIGN.md`: light Google-style surfaces, `#0B57D0` primary,
`#188038` green for low-risk outcomes, amber/red reserved for risk signals,
Inter for UI and DM Mono for every distance, duration, and score.

## Pattern references (interaction patterns only, no code copied)

- Linear (https://linear.app): product-first opening with the app itself as
  the hero visual; restrained light theme; small live-status chips.
- Stripe (https://stripe.com): asymmetric bento feature grids where larger
  cells carry miniature live UI instead of icons-only cards.
- Google Maps Platform (https://developers.google.com/maps): map-dominant
  visuals with floating context cards; matches the in-app layout language.
- Vercel status (https://www.vercel-status.com): component uptime rows used
  as the model for the "honest ops status" bento cell.

No licensed code was imported; the page is original implementation on the
existing React + react-router stack, reusing the app's i18n, wordmark, and
design tokens.

## Decisions

- Hero: left copy column (live alert chip, headline, CTAs, animated metric
  rail) against a framed product screenshot; dashed route polylines animate
  behind the section as the ambient layer.
- How it works: numbered rail with sticky intro column and per-step miniature
  UI mocks, not equal cards.
- Risk comparison: two proportional risk-segment bars (fastest vs lower-risk)
  with DM Mono stats, winner row tinted green, "why" facts as chips.
- Features: 12-column bento (one double-height live-alert cell, five smaller
  cells with mini visuals: quote bubble, saved-route chip, proactive banner,
  quota meter, status rows).
- AI teaser: static chat mock of the NL2Opt flow with parsed-constraint
  chips, recommended route card, and cited sources.
- Pricing: Free / Pro ($19, highlighted) / Team ($49, contact us); quotas
  match the in-app pricing page; footnote states checkout is not wired.
- Motion: IntersectionObserver fade-up reveals, count-up metrics, pulsing
  live dots, hover lift; all disabled under `prefers-reduced-motion`.
- Screenshots: `web/public/images/map-route-live.png` is copied from
  `docs/screenshots/map-route-live.png` (our own product capture).

## Follow-ups

- E2E specs that start from `/` still assert the old home page; they need a
  pass to target `/app` (deep-link specs keep working via redirects).
