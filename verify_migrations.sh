#!/bin/bash
set -e
for i in $(seq 1 90); do
  pg_isready -U atmospath -d atmospath >/dev/null 2>&1 && break
  sleep 1
done
# Wait for the image post-init (creates timescaledb_toolkit) to finish so its
# CREATE EXTENSION calls cannot race the migrations.
for i in $(seq 1 90); do
  [ "$(psql -tAc "SELECT 1 FROM pg_extension WHERE extname='timescaledb_toolkit'" -U atmospath -d atmospath 2>/dev/null)" = "1" ] && break
  sleep 1
done
sleep 2
for f in $(ls /m/V0*.sql | sort -V); do
  echo "== $f"
  psql -v ON_ERROR_STOP=1 -q -U atmospath -d atmospath -f "$f" > /tmp/out.log 2>&1 || { echo "FAILED: $f"; tail -25 /tmp/out.log; exit 1; }
  grep -Ei "warning|error" /tmp/out.log | head -3 || true
done
echo ALL_MIGRATIONS_OK
