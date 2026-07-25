#!/bin/bash
# 02-replica-setup.sh — Runs on REPLICA to perform pg_basebackup from primary.
# This script is used as the container entrypoint for the replica service.
set -euo pipefail

PGDATA="${PGDATA:-/var/lib/postgresql/data}"
PRIMARY_HOST="${PRIMARY_HOST:-postgres-primary}"
PRIMARY_PORT="${PRIMARY_PORT:-5432}"
REPL_USER="${REPL_USER:-replicator}"
REPL_PASSWORD="${REPL_PASSWORD:-replicator_password}"

echo "==> Waiting for primary at ${PRIMARY_HOST}:${PRIMARY_PORT}..."
until pg_isready -h "$PRIMARY_HOST" -p "$PRIMARY_PORT" -U "$REPL_USER" -q 2>/dev/null; do
    echo "    Primary not ready, retrying in 2s..."
    sleep 2
done

# If data directory already has a valid cluster, skip base backup
if [ -f "${PGDATA}/PG_VERSION" ]; then
    echo "==> Existing data directory found, skipping pg_basebackup."
else
    echo "==> Performing pg_basebackup from primary..."
    rm -rf "${PGDATA:?}"/*

    PGPASSWORD="$REPL_PASSWORD" pg_basebackup \
        -h "$PRIMARY_HOST" \
        -p "$PRIMARY_PORT" \
        -U "$REPL_USER" \
        -D "$PGDATA" \
        -Fp -Xs -P -R \
        --checkpoint=fast

    # pg_basebackup -R creates standby.signal and writes primary_conninfo
    # to postgresql.auto.conf automatically.

    echo "==> Base backup complete. Replica configured for streaming replication."
fi

# Start PostgreSQL in standby mode (standby.signal presence triggers this)
echo "==> Starting PostgreSQL replica..."
exec postgres
