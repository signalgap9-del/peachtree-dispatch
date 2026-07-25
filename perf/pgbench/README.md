# SaaS Data Layer Load Tests (pgbench)

Load test scripts for the AtmosPath SaaS data layer using PostgreSQL's built-in
`pgbench` tool. These exercise quota consumption, alert triggering, and mixed
read workloads against a realistic seeded dataset.

## Prerequisites

- PostgreSQL 16+ client tools (`pgbench`, `psql`, `pg_isready`)
- Docker with the relational compose profile
- SaaS schema migrations applied

## Quick Start

```bash
# Start PostgreSQL with PostGIS
docker compose --profile relational up -d postgres

# Wait for health check, then run all benchmarks
cd perf/pgbench
chmod +x run_load_test.sh
./run_load_test.sh
```

## Configuration

Environment variables (all optional):

| Variable   | Default       | Description                    |
|------------|---------------|--------------------------------|
| `PGHOST`   | `localhost`   | PostgreSQL host                |
| `PGPORT`   | `5432`        | PostgreSQL port                |
| `PGUSER`   | `atmospath`   | Database user                  |
| `PGPASSWORD` | `atmospath-local` | Database password        |
| `PGDATABASE` | `atmospath` | Database name                  |
| `CLIENTS`  | `100`         | Concurrent pgbench connections |
| `DURATION` | `60`          | Test duration in seconds       |

## Scripts

| Script                | Workload                                      | Default Clients |
|-----------------------|-----------------------------------------------|-----------------|
| `01_setup.sql`        | Seeds 100 tenants, 500 members, 1000 routes   | n/a (psql)      |
| `02_quota_consume.sql`| `check_and_consume_quota()` stored function   | 100             |
| `03_alert_trigger.sql`| Alert INSERT + state transition               | 50              |
| `04_mixed_read.sql`   | Routes + observations + usage reads           | 100             |

## Running Individual Scripts

```bash
# Seed data only
psql -U atmospath -d atmospath -f 01_setup.sql

# Quota consumption only (200 clients, 120 seconds)
CLIENTS=200 DURATION=120 ./run_load_test.sh

# Or run pgbench directly
pgbench -U atmospath -d atmospath -f 04_mixed_read.sql -c 100 -T 60 -P 10
```

## Interpreting Results

The runner saves timestamped logs to `/tmp/pgbench_*.log`. Key metrics:

- **tps**: transactions per second (higher is better)
- **latency average**: mean response time in ms
- **percentile latency**: p95/p99 from the `-r` per-statement breakdown

Target baselines for the SaaS data layer:

| Workload       | Target TPS | Target p99 Latency |
|----------------|------------|--------------------|
| Quota consume  | > 5,000    | < 10 ms            |
| Alert trigger  | > 2,000    | < 20 ms            |
| Mixed read     | > 8,000    | < 5 ms             |
