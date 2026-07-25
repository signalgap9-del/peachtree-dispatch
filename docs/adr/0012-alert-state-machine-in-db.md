# ADR 0012: Alert State Machine Enforced in the Database

## Status

Accepted

## Context

Alert events follow a strict lifecycle: `OPEN → ACKNOWLEDGED → RESOLVED`, with
an optional `ESCALATED` state reachable from `OPEN` or `ACKNOWLEDGED`. The
transition rules are:

```
OPEN → ACKNOWLEDGED
OPEN → ESCALATED
OPEN → RESOLVED
ACKNOWLEDGED → ESCALATED
ACKNOWLEDGED → RESOLVED
ESCALATED → RESOLVED
```

Any other transition (e.g. `RESOLVED → OPEN`, `ESCALATED → ACKNOWLEDGED`) is
invalid and must be rejected.

The naive implementation validates transitions in application code:

```java
if (event.getState() == RESOLVED && newState != RESOLVED) {
    throw new InvalidTransitionException(...);
}
```

This has two failure modes:

1. **Bypass by bug.** Any code path that writes directly to the `alert_event`
   table (a new service, a migration script, an admin tool, a CDC consumer)
   can skip the Java validation. The state machine exists in N places and
   drifts.
2. **Race conditions.** Two concurrent requests can both read `state = 'OPEN'`,
   both decide the transition is valid, and both write conflicting states.
   Application-level optimistic locking helps but adds retry complexity.

## Decision

Enforce state transitions exclusively through a **PostgreSQL stored function**
(`transition_alert_state()`) combined with a **CHECK constraint** on the
`state` column. Application code calls the function; it never writes `state`
directly.

### Database-level enforcement

1. **CHECK constraint** on `alert_event.state` restricts values to
   `('OPEN', 'ACKNOWLEDGED', 'ESCALATED', 'RESOLVED')`. This prevents invalid
   state values regardless of the write path.

2. **Stored function** `transition_alert_state(p_alert_event_id, p_new_state, p_actor_member_id)`:
   - Acquires a row lock with `SELECT ... FOR UPDATE`.
   - Validates the transition against the allowed set.
   - Raises `check_violation` on invalid transitions.
   - Updates `state` and sets `resolved_at` when transitioning to `RESOLVED`.
   - Writes an `audit_log` entry in the same transaction.
   - Returns the updated row.

3. **Column-level privilege.** The application runtime role has no direct
   `UPDATE` privilege on `alert_event.state`. It can only modify state through
   `SECURITY DEFINER` functions that enforce the transition rules.

### Application contract

```java
// AlertEventService.java
public AlertEvent transition(UUID alertEventId, AlertState newState, UUID actorId) {
    try {
        return jdbcTemplate.queryForObject(
            "SELECT * FROM transition_alert_state(?, ?, ?)",
            alertEventRowMapper, alertEventId, newState.name(), actorId);
    } catch (DataIntegrityViolationException e) {
        throw new InvalidAlertTransitionException(alertEventId, newState);
    }
}
```

The application maps `check_violation` (SQLSTATE 23514) to HTTP 409 Conflict
with a structured error body identifying the invalid transition.

## Alternatives Considered

| Option | Why not |
| --- | --- |
| **App-level enum validation** | Can be bypassed by any code path that writes to the table directly. The state machine must be duplicated in every service, script, and consumer that touches `alert_event`. Drift is inevitable. |
| **Event sourcing** | Store every state change as an immutable event and derive current state by replay. Correct but operationally heavy: requires an event store, projection infrastructure, and snapshot management. Overkill for a four-state lifecycle at portfolio scale. |
| **Database trigger** | A `BEFORE UPDATE` trigger can validate transitions, but triggers are invisible to application developers debugging behavior. A named stored function is explicit, callable, and testable. The function also bundles the audit-log write, which a trigger cannot do cleanly. |
| **Optimistic locking with version column** | Prevents concurrent overwrites but does not prevent invalid transitions. A request can still write `RESOLVED → OPEN` if it holds the correct version. The version column is complementary, not sufficient. |

## Consequences

- **The database is the single source of truth for alert state.** Every write
  path (API, CDC consumer, admin tool, migration) must call
  `transition_alert_state()`. Direct `UPDATE alert_event SET state = ...` is
  blocked by column privileges.
- **Concurrent transitions are safe.** `SELECT ... FOR UPDATE` serializes
  transitions on the same row. The second request sees the updated state and
  either proceeds or fails with `check_violation`.
- **Audit trail is automatic.** Every transition writes an `audit_log` row in
  the same transaction. No application code can forget the audit entry.
- **Testing shifts to the database.** State machine tests run SQL directly
  against a test database (Testcontainers or Docker Compose) rather than
  mocking application-level validation. This is more realistic but requires a
  running PostgreSQL instance in CI.
- **Schema coupling.** Adding a new state (e.g. `SNOOZED`) requires a
  migration that updates the CHECK constraint, the stored function's CASE
  expression, and the application enum. This is intentional: state changes are
  rare and should be deliberate.
