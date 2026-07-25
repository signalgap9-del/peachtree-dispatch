#!/bin/bash
# 01-replication.sh — Runs on PRIMARY to create a replication user.
# Mounted into /docker-entrypoint-initdb.d/ on the primary container.
set -euo pipefail

echo "==> Creating streaming replication user..."

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    -- Replication role with login
    DO \$\$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'replicator') THEN
            CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD 'replicator_password';
        END IF;
    END
    \$\$;

    -- Allow replication connections from the Docker network
    -- (pg_hba.conf entry added via command args on the primary)
EOSQL

echo "==> Replication user 'replicator' ready."
