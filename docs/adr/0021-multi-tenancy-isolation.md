# ADR 0021: Multi-Tenancy Isolation Tradeoff (RLS vs Schema vs Database per Tenant)

## Status

Accepted

## Context

FreightScaler is multi-tenant at the database layer. V009 enables
`ROW LEVEL SECURITY` (and `FORCE ROW LEVEL SECURITY`) on every tenant-scoped
table - `tenant`, `tenant_member`, `workspace`, `workspace_member`,
`subscription`, `entitlement`, `usage_record`, `saved_route`, `saved_place`,
`alert_event`, `alert_escalation`, `audit_log` - plus `idempotency_key` and
`api_key` in V011. Each policy filters on `current_tenant_id()`, which reads
the `app.tenant_id` GUC. The application sets it with `SET LOCAL` at
connection checkout (ADR-0013), which is compatible with PgBouncer
`transaction` pooling.

The question this ADR settles: is shared-schema RLS the right isolation
model for where the product is, and what would make us escalate? Three
standard PostgreSQL multi-tenancy models exist:

1. **Shared schema + RLS (current).** One set of tables, policies enforce
   tenant boundaries per row.
2. **Schema-per-tenant.** One PostgreSQL schema per tenant; connections switch
   `search_path`; tables repeat per schema.
3. **Database-per-tenant.** One physical database per tenant, typically one
   instance (or shard) per large customer.

Constraints that shape the choice:

- Scale trajectory from ADR-0020: ~1k users now, planning headroom to ~1M.
  Tenants number in the thousands, not dozens; the median tenant is tiny.
- The team is small. Every operational multiplier (migrations x N schemas,
  pools x N databases) is a real cost, not a theoretical one.
- Isolation must survive application bugs. RLS policies are evaluated by the
  engine regardless of what the query forgot to filter.
- All access paths are already tenant-scoped by construction (RLS forced),
  so the isolation boundary is enforced and exercised today.

## Decision

Keep **shared schema with row-level security** as the isolation model.
Document explicit escalation triggers for the other two models rather than
adopting them speculatively.

### Comparison

| Dimension | Shared schema + RLS (chosen) | Schema-per-tenant | Database-per-tenant |
| --- | --- | --- | --- |
| Isolation strength | Logical, engine-enforced per row | Logical, namespace-level; no cross-schema accident is possible without explicit qualification | Physical; separate storage, connections, failure domain |
| Blast radius of a DB incident | All tenants | All tenants (same instance) | One tenant |
| Ops cost | 1 schema, 1 Flyway run, 1 pool, 1 monitoring target | Flyway x N schemas, `search_path` switching per connection, catalog bloat (`pg_class` x N), per-schema vacuum/index stats | N connection pools, N backup schedules, N migration jobs, N monitoring targets |
| Per-tenant backup / PITR | No (whole-DB) | No (whole-DB) | Yes |
| Noisy-neighbor containment | None; one tenant's heavy query shares buffers/CPU with everyone | Weak; same instance | Strong; dedicated capacity |
| Compliance posture | Shared storage; isolation provable via policies + audit | Stronger logical separation; still shared storage | Dedicated storage; supports residency and dedicated-capacity clauses |
| Cross-tenant analytics | Trivial (one schema) | Union across schemas or ETL | Requires warehouse/CDC fan-in |
| Fit for our tenant distribution | Excellent: thousands of small tenants | Hundreds of mid-size tenants | Dozens of large enterprise tenants |
| Implementation state | Done (V009/V011), exercised by the app | Greenfield rewrite of DDL + migration tooling | Greenfield + routing layer |

### Why RLS now

- **It is sufficient isolation for the threat model.** Tenant A must not read
  or mutate tenant B's rows. RLS enforces this inside the engine; `FORCE ROW
  LEVEL SECURITY` closes the table-owner bypass, and fail-safe default is
  zero rows: if `app.tenant_id` is unset, `current_tenant_id()` is NULL and
  every policy evaluates false. An application bug that forgets a WHERE
  clause still cannot leak cross-tenant data.
- **Operational leverage.** One Flyway history, one replica topology
  (ADR-0013), one PgBouncer pool, one `pg_stat_statements` target (V017).
  Schema-per-tenant multiplies migrations and catalog maintenance by tenant
  count; at thousands of tenants that is a full-time job the team does not
  have.
- **It aligns with the scaling path.** ADR-0020 shards by `tenant_id` later.
  RLS predicates already carry the shard key in every query, so the sharding
  migration is a routing change, not a query rewrite. Schema-per-tenant would
  instead converge on the same physical shape (many schemas -> many shards)
  after paying years of extra ops cost to get there.
- **Analytics stay simple.** Fleet-wide materialized views
  (`mv_workspace_usage_summary`, `mv_tenant_risk_summary`) are plain queries.
  Both alternative models push these into ETL before the product needs that.

### Known RLS costs, accepted and mitigated

- **Policy evaluation overhead.** `saved_route`/`saved_place`/
  `workspace_member`/`alert_escalation` policies use `EXISTS` subqueries
  through `tenant_member` or the parent table. These are index-backed
  (PK/FK lookups) and measured cheap at current scale via
  `pg_stat_statements`; revisit if they appear in the top-N by
  `mean_exec_time`. Denormalizing `tenant_id` onto `saved_route`/
  `saved_place` (already required on `route_risk_observation` for sharding,
  ADR-0020) would flatten these policies to direct comparisons - recommended
  as a follow-up.
- **No per-tenant resource isolation.** A runaway tenant query affects shared
  buffers and CPU. Mitigations now: PgBouncer pool limits, statement
  timeouts, replica offload for reads. This is the dimension most likely to
  force escalation.
- **Whole-database backup/PITR.** Erasure and restore are fleet-wide
  operations; [`data-retention.md`](../data-retention.md) documents how
  erasure propagates and how backups age out.
- **GUC discipline.** Isolation depends on `SET LOCAL app.tenant_id` running
  in every transaction. The data-source interceptor does this at checkout;
  background jobs that touch tenant data must set it explicitly or run as a
  `BYPASSRLS` role with deliberate scoping (see the V017 note on MVIEW
  refresh).

### Escalation triggers

**To schema-per-tenant** (stronger logical isolation, still one instance):

- A compliance audit or procurement requirement demands demonstrable logical
  separation beyond row policies (e.g. per-tenant access logging at the
  relation level), or
- Mid-market tenants (hundreds) begin requiring per-tenant extension or DDL
  freedom, or per-tenant migration windows.

**To database-per-tenant** (physical isolation):

- A large enterprise contract requires dedicated capacity, per-tenant
  backup/PITR, or data residency that pins one tenant's data to one region/
  account, or
- Noisy-neighbor incidents recur despite pooling and timeouts, and the
  affected tenants justify dedicated instances.

Either escalation is compatible with ADR-0020: tenant-keyed sharding is the
natural physical realization of database-per-tenant, and the `tenant_id`
denormalization work serves all three models.

## Alternatives Considered

| Option | Why not (now) |
| --- | --- |
| **Schema-per-tenant from the start** | Multiplies Flyway runs, catalog maintenance, and connection management by tenant count for isolation strength the current threat model does not require. Cross-tenant analytics become ETL. Revisit at the triggers above. |
| **Database-per-tenant from the start** | Maximum ops cost for a tenant base that is overwhelmingly small self-serve accounts. Justified only by enterprise contracts we do not have. |
| **Application-layer filtering only (no RLS)** | One forgotten WHERE clause becomes a cross-tenant data leak. Rejected outright for a product handling location data; engine-enforced isolation is the bar. |
| **RLS without FORCE** | Leaves the table-owner bypass open. `FORCE` is already in V009 and is load-bearing for the fail-closed default. |

## Consequences

- Isolation stays engine-enforced, fail-closed, and cheap to operate: one
  schema, one migration history, one pool, one replica topology.
- The trade is shared fate: no per-tenant blast-radius containment,
  backup/PITR, or residency story. Those are purchased on demand via the
  documented escalation triggers, not pre-built.
- Every new tenant-scoped table must ship with an RLS policy (`ENABLE` +
  `FORCE` + policy on `current_tenant_id()`); this is a review checklist item
  for migrations going forward.
- `BYPASSRLS` roles are a privileged surface: they exist only for the
  migration role (dev superuser / RDS `rds_superuser`) and any future
  background maintenance role, each documented where introduced.
- The recommended follow-up - denormalize `tenant_id` onto `saved_route`,
  `saved_place`, and `route_risk_observation` - improves policy cost now and
  is a prerequisite for sharding later, so it pays for itself twice.
