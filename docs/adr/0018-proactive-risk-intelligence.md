# ADR 0018: Proactive Risk Intelligence

## Status

Accepted

## Context

Users save routes to a watchlist with monitoring thresholds. Without proactive
intelligence, users must manually check each saved route for risk changes.
This is impractical for fleet operators monitoring dozens of routes, and it
defeats the purpose of threshold-based monitoring: the system should alert
users when conditions change, not wait for users to ask.

However, not every threshold breach warrants a notification. A 2% risk
increase on a route the user drives daily is noise. A sudden flood warning
on a route with cargo arriving in 4 hours is critical. The system needs
judgment, not just threshold comparison.

## Decision

Implement **scheduled analysis + LLM judgment + SSE push** for proactive
risk notifications:

### Scheduled analysis

- A scheduled task (configurable interval, default 15 minutes) scans saved
  routes with monitoring enabled.
- For each route, fetch current risk scores from the risk engine.
- Compare against the user's configured threshold.

### LLM judgment layer

- When a threshold breach is detected, the LLM assesses relevance:
  "Given this user's saved route from X to Y with threshold Z, and the
  current risk change [details], should this user be notified?"
- The LLM considers: severity of change, time sensitivity, user's route
  history, and whether the risk is transient or structural.
- Only breaches the LLM judges as actionable generate notifications.

### SSE push

- Actionable notifications are pushed via Server-Sent Events to connected
  clients in real time.
- Notification includes: route name, risk delta, LLM-generated summary,
  and suggested action (reroute, delay departure, monitor).

### Deduplication window

- A 6-hour dedup window prevents notification spam for the same route +
  risk type combination.
- Stored in Redis with TTL; falls back to in-memory for single-instance.
- If risk escalates significantly (>2x previous alert level), the dedup
  window is bypassed.

### Why LLM judgment over pure thresholds

| Approach | Problem |
| --- | --- |
| Pure threshold | Every breach notifies; high noise, alert fatigue |
| Pure LLM (no threshold) | Expensive; LLM called for every route every cycle |
| Threshold + LLM | LLM called only on breaches; filters noise |

The threshold acts as a cheap pre-filter. The LLM adds contextual judgment
only when the cheap check flags something. This keeps LLM costs proportional
to actual risk events, not to the number of monitored routes.

## Consequences

- LLM cost scales with threshold breaches, not total monitored routes.
- Dedup window means users see at most one notification per risk type per
  6 hours unless severity escalates.
- SSE requires a persistent connection; disconnected clients miss pushes
  (mitigated by the alerts page showing recent history).
- The LLM judgment prompt must be tuned to avoid both over-alerting
  (noise) and under-alerting (missed critical events).
- Evaluation: 20 conversation scenarios include proactive notification
  relevance checks.
