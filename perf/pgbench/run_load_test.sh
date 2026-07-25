#!/usr/bin/env bash
# AtmosPath SaaS data layer load test runner
# Requires: PostgreSQL running via docker compose --profile relational up -d postgres
#           pgbench (ships with PostgreSQL client tools)

set -euo pipefail

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-atmospath}"
PGDATABASE="${PGDATABASE:-atmospath}"
export PGPASSWORD="${PGPASSWORD:-atmospath-local}"

CLIENTS="${CLIENTS:-100}"
DURATION="${DURATION:-60}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=== AtmosPath SaaS Load Test ==="
echo "Target: ${PGHOST}:${PGPORT}/${PGDATABASE}"
echo "Clients: ${CLIENTS}, Duration: ${DURATION}s"
echo ""

# Wait for PostgreSQL to be ready
echo "Waiting for PostgreSQL..."
until pg_isready -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -q; do
    sleep 1
done
echo "PostgreSQL is ready."
echo ""

# Seed data
echo "--- Seeding data (01_setup.sql) ---"
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -f "${SCRIPT_DIR}/01_setup.sql"
echo ""

# Initialize pgbench tables (required for pgbench to run)
pgbench -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -i -s 1 2>/dev/null || true

run_benchmark() {
    local name="$1"
    local script="$2"
    local clients="${3:-$CLIENTS}"

    echo ""
    echo "=== ${name} (${clients} clients, ${DURATION}s) ==="
    pgbench -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" \
        -f "${SCRIPT_DIR}/${script}" \
        -c "$clients" \
        -T "$DURATION" \
        -P 10 \
        --progress-timestamp \
        -r \
        2>&1 | tee "/tmp/pgbench_${name}_$(date +%Y%m%d_%H%M%S).log"
    echo ""
}

# Quota consumption (write-heavy, stored function)
run_benchmark "quota_consume" "02_quota_consume.sql" "$CLIENTS"

# Alert trigger (write, transactional)
run_benchmark "alert_trigger" "03_alert_trigger.sql" 50

# Mixed read workload
run_benchmark "mixed_read" "04_mixed_read.sql" "$CLIENTS"

echo ""
echo "=== Load test complete ==="
echo "Results saved to /tmp/pgbench_*.log"
echo ""
echo "Key metrics to review:"
echo "  - tps (transactions per second)"
echo "  - latency average and stddev"
echo "  - percentile latency (p95, p99) from -r output"
