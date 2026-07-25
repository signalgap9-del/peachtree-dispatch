# Runbook: Production Data Stack

This runbook covers day-to-day operations for the local production data stack:
PostgreSQL 16 (TimescaleDB), Redis 7, Kafka + Debezium CDC, PgBouncer, and the
read replica. All services run in Docker Compose under the `production-data`
profile.

---

## Starting the Stack

```powershell
# Start all production-data services
docker compose --profile production-data up -d

# Verify containers are running
docker compose --profile production-data ps
```

Expected containers:

| Container | Image | Port | Purpose |
| --- | --- | --- | --- |
| `postgres` | `timescale/timescaledb-ha:pg16` | 5432 | Primary (writes) |
| `postgres-replica` | `timescale/timescaledb-ha:pg16` | 5433 | Replica (reads) |
| `pgbouncer` | `edoburu/pgbouncer` | 6432 | Connection pooling |
| `redis` | `redis:7-alpine` | 6379 | Quota counters, rate limits, cache |
| `kafka` | `bitnami/kafka:3.7` | 9092 | CDC event bus |
| `debezium` | `debezium/connect:2.6` | 8083 | CDC connector |

Startup order matters. Docker Compose `depends_on` with health checks ensures
PostgreSQL is ready before Debezium connects. Full startup takes ~30s.

---

## Health Checks

### PostgreSQL primary

```powershell
docker compose exec postgres pg_isready -U atmospath
# Expected: /var/run/postgresql:5432 - accepting connections
```

### PostgreSQL replica

```powershell
docker compose exec postgres-replica pg_isready -U atmospath
# Expected: /var/run/postgresql:5432 - accepting connections

# Verify replica is in recovery (read-only) mode
docker compose exec postgres-replica psql -U atmospath -d atmospath -tAc "SELECT pg_is_in_recovery();"
# Expected: t
```

### Redis

```powershell
docker compose exec redis redis-cli ping
# Expected: PONG

docker compose exec redis redis-cli info server | Select-String "redis_version|uptime_in_seconds"
```

### Kafka

```powershell
# List topics
docker compose exec kafka kafka-topics.sh --bootstrap-server localhost:9092 --list
# Expected: atmospath.public.alert_event, atmospath.public.usage_record, etc.

# Check broker health
docker compose exec kafka kafka-broker-api-versions.sh --bootstrap-server localhost:9092 | Select-String "ApiVersion"
```

### Debezium

```powershell
# Check connector status
curl -s http://localhost:8083/connectors | ConvertFrom-Json
# Expected: ["atmospath-pg-connector"]

curl -s http://localhost:8083/connectors/atmospath-pg-connector/status | ConvertFrom-Json | ConvertTo-Json -Depth 5
# Expected: connector.state = "RUNNING", all tasks state = "RUNNING"
```

### PgBouncer

```powershell
docker compose exec pgbouncer psql -U atmospath -p 6432 -d pgbouncer -c "SHOW POOLS;"
# Check cl_active and cl_waiting columns
```

---

## Running Flyway Migrations

Migrations run as a deployment step, separate from application startup.

```powershell
# Run pending migrations
docker compose exec postgres flyway -url=jdbc:postgresql://localhost:5432/atmospath `
  -user=flyway_migrator -password=$env:FLYWAY_PASSWORD migrate

# Check migration status
docker compose exec postgres flyway -url=jdbc:postgresql://localhost:5432/atmospath `
  -user=flyway_migrator -password=$env:FLYWAY_PASSWORD info
```

If Flyway is not installed in the container, run via the application:

```powershell
cd services/platform-api
../../scripts/mvn.ps1 flyway:migrate "-Dflyway.url=jdbc:postgresql://localhost:5432/atmospath" `
  "-Dflyway.user=flyway_migrator" "-Dflyway.password=$env:FLYWAY_PASSWORD"
```

**Credential separation.** The `flyway_migrator` role has DDL privileges
(CREATE, ALTER, DROP). The application runtime role (`atmospath_app`) has
DML-only privileges (SELECT, INSERT, UPDATE, DELETE). Never run the
application with the migration role.

---

## Running pgbench Load Tests

```powershell
# Initialize pgbench tables (first time only)
docker compose exec postgres pgbench -i -s 10 -U atmospath atmospath

# Read-only load test (simulates dashboard queries)
docker compose exec postgres pgbench -U atmospath -c 20 -j 4 -T 60 `
  -f /scripts/read_only.sql atmospath

# Mixed read/write load test
docker compose exec postgres pgbench -U atmospath -c 20 -j 4 -T 60 atmospath

# Custom: quota increment contention test
docker compose exec postgres pgbench -U atmospath -c 50 -j 8 -T 30 `
  -f /scripts/quota_increment.sql atmospath
```

Example `read_only.sql`:

```sql
SELECT * FROM saved_route WHERE member_id = gen_random_uuid() AND deleted_at IS NULL;
SELECT * FROM route_risk_observation WHERE saved_route_id = gen_random_uuid()
  AND observed_at > now() - INTERVAL '7 days';
```

Example `quota_increment.sql`:

```sql
INSERT INTO usage_record (tenant_id, feature_code, usage_date, quantity)
VALUES (gen_random_uuid(), 'ROUTE_PLAN', CURRENT_DATE, 1)
ON CONFLICT (tenant_id, feature_code, usage_date)
DO UPDATE SET quantity = usage_record.quantity + 1;
```

**Interpreting results.**
- `tps` (transactions per second): target > 500 for read-only, > 200 for
  mixed.
- `latency average`: target < 10ms for read-only, < 50ms for mixed.
- If `quota_increment.sql` shows high latency at 50 connections, the Redis
  dual-ledger (ADR-0011) is justified.

---

## Checking Replication Lag

```powershell
# From the primary: current replication status
docker compose exec postgres psql -U atmospath -d atmospath -c "
  SELECT client_addr, state, sent_lsn, write_lsn, flush_lsn, replay_lsn,
         now() - write_lag AS write_lag,
         now() - replay_lag AS replay_lag
  FROM pg_stat_replication;"

# From the replica: last replayed LSN
docker compose exec postgres-replica psql -U atmospath -d atmospath -tAc "
  SELECT pg_last_wal_replay_lsn();"

# Quick lag check (returns interval)
docker compose exec postgres-replica psql -U atmospath -d atmospath -tAc "
  SELECT CASE
    WHEN pg_last_wal_receive_lsn() = pg_last_wal_replay_lsn()
    THEN '0 seconds'::interval
    ELSE now() - pg_last_xact_replay_timestamp()
  END AS replication_lag;"
```

**Thresholds.**
- Normal: < 100ms.
- Warning: > 1s sustained for 30s.
- Critical: > 10s. Investigate WAL sender, network, or replica I/O.

---

## Troubleshooting

### Replication slot not advancing

**Symptom.** `pg_replication_slots.confirmed_flush_lsn` stops advancing. WAL
files accumulate on the primary.

```powershell
# Check replication slots
docker compose exec postgres psql -U atmospath -d atmospath -c "
  SELECT slot_name, active, confirmed_flush_lsn,
         pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)) AS retained_wal
  FROM pg_replication_slots;"
```

**Fix.**
1. If the slot is inactive (`active = f`), the consumer (Debezium or replica)
   is down. Restart it.
2. If retained WAL exceeds 1 GB, consider dropping the slot (data loss for
   that consumer):
   ```sql
   SELECT pg_drop_replication_slot('debezium_slot');
   ```
3. Restart Debezium; it will re-snapshot the affected tables.

### PgBouncer pool exhaustion

**Symptom.** Application logs show `connection timeout` or
`no more connections allowed`. `SHOW POOLS` shows `cl_waiting > 0`.

```powershell
docker compose exec pgbouncer psql -U atmospath -p 6432 -d pgbouncer -c "SHOW POOLS;"
docker compose exec pgbouncer psql -U atmospath -p 6432 -d pgbouncer -c "SHOW STATS;"
```

**Fix.**
1. Increase `max_client_conn` (default 100) and `default_pool_size`
   (default 20) in `pgbouncer.ini`.
2. Check for leaked connections: long-running transactions holding pooled
   connections. Query `pg_stat_activity` on the primary for idle-in-transaction
   sessions:
   ```sql
   SELECT pid, state, query_start, now() - query_start AS duration, query
   FROM pg_stat_activity
   WHERE state = 'idle in transaction'
   ORDER BY duration DESC;
   ```
3. Kill leaked sessions: `SELECT pg_terminate_backend(<pid>);`
4. Ensure the application uses `SET LOCAL` (not `SET`) for tenant context.
   Session-level settings are incompatible with transaction pooling.

### Kafka consumer lag growing

**Symptom.** Alert notifications arrive late. Consumer group offset falls
behind the topic's end offset.

```powershell
# Check consumer group lag
docker compose exec kafka kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe --group atmospath-alert-consumer
```

**Fix.**
1. If `LAG` is growing steadily, the consumer is slower than the producer.
   Scale consumers (add instances to the consumer group) or optimize the
   consumer's processing logic.
2. If `LAG` spiked suddenly, check for a burst of alert events (e.g. a
   severe weather event). The lag should drain once the burst passes.
3. If the consumer is stuck (`LAG` not changing, `CURRENT-OFFSET` frozen),
   check consumer logs for exceptions. Restart the consumer.
4. For persistent lag, increase `max.poll.records` and
   `max.poll.interval.ms` in the consumer configuration.

### Redis OOM or persistence failure

**Symptom.** `MISCONF Redis is configured to save RDB snapshots, but it is
currently not able to persist on disk` or `OOM command not allowed`.

```powershell
docker compose exec redis redis-cli info memory | Select-String "used_memory_human|maxmemory_human"
docker compose exec redis redis-cli info persistence | Select-String "rdb_last_bgsave_status|aof_last_write_status"
```

**Fix.**
1. Set `maxmemory 256mb` and `maxmemory-policy allkeys-lru` in `redis.conf`.
2. For AOF persistence errors, check disk space on the Docker host.
3. After Redis restart, the `UsageReconciliationJob` seeds counters from the
   database on startup. Verify quota counters match:
   ```powershell
   docker compose exec redis redis-cli keys "quota:*" | Measure-Object -Line
   ```

### Debezium connector fails to start

**Symptom.** Connector status shows `FAILED` with a `PSQLException`.

```powershell
curl -s http://localhost:8083/connectors/atmospath-pg-connector/status | ConvertFrom-Json | ConvertTo-Json -Depth 5
```

**Fix.**
1. Verify `wal_level = logical` on the primary:
   ```sql
   SHOW wal_level;
   ```
2. Verify the replication user exists and has `REPLICATION` privilege:
   ```sql
   SELECT rolname, rolreplication FROM pg_roles WHERE rolname = 'debezium';
   ```
3. Verify `pg_hba.conf` allows the Debezium container's IP.
4. If the replication slot was dropped, restart the connector to recreate it:
   ```powershell
   curl -X POST http://localhost:8083/connectors/atmospath-pg-connector/restart
   ```

---

## Stopping the Stack

```powershell
# Graceful stop
docker compose --profile production-data down

# Stop and remove volumes (destroys all data)
docker compose --profile production-data down -v
```

**Warning.** `down -v` deletes all PostgreSQL data, Redis state, and Kafka
topics. Use only for a clean reset.
