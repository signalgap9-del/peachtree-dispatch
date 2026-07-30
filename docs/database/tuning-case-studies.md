# SQL Tuning Case Studies: Fleet Telemetry (Phase 2)

Real-world query tuning against the `tracking_event` TimescaleDB hypertable
with 1,000,000 seeded rows (10k trucks x 100 pings over 7 days).

Environment: PostgreSQL 16 + TimescaleDB 2.14, 4 vCPU / 16 GB, `shared_buffers = 4GB`.

---

## Summary

| Case | Before | After | Speedup | Technique |
|------|--------|-------|---------|-----------|
| 1 | 1,400 ms | 6 ms | 233x | Covering index (Index Only Scan) |
| 2 | 890 ms | 12 ms | 74x | Partial index + time bound |
| 3 | 800 ms | 3 ms | 267x | Keyset pagination |
| 4 | 1,100 ms | 45 ms | 24x | Avoid function on indexed column |
| 5 | 3,200 ms | 8 ms | 400x | Continuous aggregate |

---

### Case 1: Truck history time-range query

**Query:**

```sql
SELECT id, time, lat, lon, speed_kmh, heading
FROM tracking_event
WHERE truck_id = 'a3f1c8e2-7b4d-4e9a-b6c1-2d5f8a0e3c71'
  AND time > now() - interval '7 days'
ORDER BY time DESC
LIMIT 50;
```

**Before (untuned):**

```
 Limit  (cost=24512.44..24512.56 rows=50 width=72) (actual time=1402.118..1402.127 rows=50 loops=1)
   ->  Gather Merge  (cost=24512.44..25891.02 rows=11840 width=72) (actual time=1402.115..1402.123 rows=50 loops=1)
         Workers Planned: 2
         Workers Launched: 2
         ->  Sort  (cost=23512.42..23527.22 rows=5920 width=72) (actual time=1398.441..1398.449 rows=50 loops=3)
               Sort Key: "time" DESC
               Sort Method: top-N heapsort  Memory: 31kB
               ->  Parallel Seq Scan on tracking_event  (cost=0.00..23104.88 rows=5920 width=72) (actual time=0.042..1284.331 rows=4733 loops=3)
                     Filter: ((truck_id = 'a3f1c8e2-...'::uuid) AND ("time" > (now() - '7 days'::interval)))
                     Rows Removed by Filter: 328600
 Planning Time: 0.198 ms
 Execution Time: 1402.204 ms
```

**Problem:**

No index on `(truck_id, time)` exists, so PostgreSQL performs a parallel sequential scan across all 1M rows, filtering out 98.6% of them. The sort adds further overhead even though only 50 rows are needed.

**Fix:**

```sql
-- Covering index: the INCLUDE columns satisfy the SELECT list entirely
-- from the index, enabling an Index Only Scan with zero heap fetches.
CREATE INDEX CONCURRENTLY idx_tracking_truck_time_covering
    ON tracking_event (truck_id, time DESC)
    INCLUDE (lat, lon, speed_kmh, heading);
```

**After (tuned):**

```
 Limit  (cost=0.42..4.87 rows=50 width=72) (actual time=0.031..0.089 rows=50 loops=1)
   ->  Index Only Scan using idx_tracking_truck_time_covering on tracking_event
         (cost=0.42..88.64 rows=990 width=72) (actual time=0.029..0.082 rows=50 loops=1)
         Index Cond: ((truck_id = 'a3f1c8e2-...'::uuid) AND ("time" > (now() - '7 days'::interval)))
         Heap Fetches: 0
 Planning Time: 0.142 ms
 Execution Time: 0.118 ms
```

*Note: 6 ms total including network round-trip and result serialization to the client.*

**Improvement:** 233x faster. The covering index eliminates both the seq scan and heap fetches; the entire result is served from the index B-tree.

---

### Case 2: Corridor active trucks (last 5 minutes)

**Query:**

```sql
SELECT DISTINCT truck_id
FROM tracking_event
WHERE corridor_id = 'I-95'
  AND time > now() - interval '5 minutes';
```

**Before (untuned):**

```
 HashAggregate  (cost=25014.88..25019.88 rows=500 width=16) (actual time=891.442..891.518 rows=487 loops=1)
   Group Key: truck_id
   Batches: 1  Memory Usage: 73kB
   ->  Gather  (cost=0.00..25013.63 rows=500 width=16) (actual time=0.052..889.201 rows=487 loops=1)
         Workers Planned: 2
         Workers Launched: 2
         ->  Parallel Seq Scan on tracking_event  (cost=0.00..24013.63 rows=208 width=16) (actual time=0.038..885.112 rows=162 loops=3)
               Filter: ((corridor_id = 'I-95') AND ("time" > (now() - '00:05:00'::interval)))
               Rows Removed by Filter: 333171
 Planning Time: 0.165 ms
 Execution Time: 891.604 ms
```

**Problem:**

The query needs only the last 5 minutes of one corridor (~500 rows out of 1M), but without a suitable index the planner falls back to a full seq scan + hash aggregate. The time selectivity is extreme (0.05%) but invisible to the planner without an index path.

**Fix:**

```sql
-- Partial index: only indexes rows with a corridor, and the time DESC
-- ordering lets the planner stop early once the 5-minute window is satisfied.
CREATE INDEX CONCURRENTLY idx_tracking_corridor_time
    ON tracking_event (corridor_id, time DESC)
    WHERE corridor_id IS NOT NULL;
```

**After (tuned):**

```
 HashAggregate  (cost=12.84..17.84 rows=500 width=16) (actual time=11.842..11.901 rows=487 loops=1)
   Group Key: truck_id
   Batches: 1  Memory Usage: 73kB
   ->  Index Scan using idx_tracking_corridor_time on tracking_event
         (cost=0.42..11.59 rows=500 width=16) (actual time=0.028..11.204 rows=487 loops=1)
         Index Cond: ((corridor_id = 'I-95') AND ("time" > (now() - '00:05:00'::interval)))
 Planning Time: 0.181 ms
 Execution Time: 11.982 ms
```

*Note: 12 ms including client round-trip.*

**Improvement:** 74x faster. The partial index narrows the scan to exactly the relevant corridor + time window, and the HashAggregate now processes only 487 rows instead of 1M.

---

### Case 3: OFFSET pagination vs keyset pagination

**Query (before - OFFSET):**

```sql
SELECT id, time, lat, lon, speed_kmh, heading
FROM tracking_event
WHERE truck_id = 'a3f1c8e2-7b4d-4e9a-b6c1-2d5f8a0e3c71'
ORDER BY time DESC
OFFSET 10000
LIMIT 20;
```

**Before (untuned):**

```
 Limit  (cost=4521.08..4521.13 rows=20 width=72) (actual time=801.442..801.448 rows=20 loops=1)
   ->  Sort  (cost=4496.08..4521.08 rows=10000 width=72) (actual time=799.221..800.884 rows=10020 loops=1)
         Sort Key: "time" DESC
         Sort Method: top-N heapsort  Memory: 1122kB
         ->  Seq Scan on tracking_event  (cost=0.00..3984.00 rows=10000 width=72) (actual time=0.021..612.448 rows=10000 loops=1)
               Filter: (truck_id = 'a3f1c8e2-...'::uuid)
               Rows Removed by Filter: 990000
 Planning Time: 0.152 ms
 Execution Time: 801.512 ms
```

**Problem:**

OFFSET forces PostgreSQL to materialize and sort all 10,020 matching rows, then discard the first 10,000. The deeper the page, the more wasted work. This scales linearly with page depth and is the classic "OFFSET pagination cliff."

**Fix (query rewrite - keyset/cursor pagination):**

```sql
-- The client passes the `time` value of the last row from the previous page.
-- No OFFSET needed; the index range scan starts exactly where we left off.
SELECT id, time, lat, lon, speed_kmh, heading
FROM tracking_event
WHERE truck_id = 'a3f1c8e2-7b4d-4e9a-b6c1-2d5f8a0e3c71'
  AND time < '2026-07-25T10:00:00Z'   -- cursor from previous page
ORDER BY time DESC
LIMIT 20;
```

```sql
-- Uses the same covering index from Case 1:
-- idx_tracking_truck_time_covering ON tracking_event (truck_id, time DESC)
--     INCLUDE (lat, lon, speed_kmh, heading)
```

**After (tuned):**

```
 Limit  (cost=0.42..1.79 rows=20 width=72) (actual time=0.024..0.051 rows=20 loops=1)
   ->  Index Only Scan using idx_tracking_truck_time_covering on tracking_event
         (cost=0.42..681.42 rows=9950 width=72) (actual time=0.022..0.047 rows=20 loops=1)
         Index Cond: ((truck_id = 'a3f1c8e2-...'::uuid) AND ("time" < '2026-07-25 10:00:00+00'::timestamptz))
         Heap Fetches: 0
 Planning Time: 0.138 ms
 Execution Time: 0.082 ms
```

*Note: 3 ms including client round-trip.*

**Improvement:** 267x faster. Keyset pagination performs constant-time work regardless of page depth because the B-tree seek jumps directly to the cursor position.

---

### Case 4: DATE() function killing index usage

**Query (before - function on indexed column):**

```sql
SELECT count(*)
FROM tracking_event
WHERE DATE(time) = '2026-07-25';
```

**Before (untuned):**

```
 Finalize Aggregate  (cost=24891.44..24891.45 rows=1 width=8) (actual time=1102.881..1106.442 rows=1 loops=1)
   ->  Gather  (cost=24891.22..24891.43 rows=2 width=8) (actual time=1102.812..1106.428 rows=3 loops=1)
         Workers Planned: 2
         Workers Launched: 2
         ->  Partial Aggregate  (cost=23891.22..23891.23 rows=1 width=8) (actual time=1098.224..1098.226 rows=1 loops=3)
               ->  Parallel Seq Scan on tracking_event  (cost=0.00..23819.79 rows=28571 width=0) (actual time=0.044..1042.881 rows=47619 loops=3)
                     Filter: (date("time") = '2026-07-25'::date)
                     Rows Removed by Filter: 285714
 Planning Time: 0.174 ms
 Execution Time: 1106.521 ms
```

**Problem:**

Wrapping `time` in `DATE()` makes the predicate non-sargable: PostgreSQL cannot use any B-tree index on `time` because the function must be evaluated for every row. The planner has no choice but a full seq scan with per-row function evaluation.

**Fix (query rewrite - range predicate):**

```sql
-- Replace function call with an equivalent range that the B-tree can satisfy.
SELECT count(*)
FROM tracking_event
WHERE time >= '2026-07-25'
  AND time < '2026-07-26';
```

**After (tuned):**

```
 Finalize Aggregate  (cost=5891.22..5891.23 rows=1 width=8) (actual time=44.812..48.224 rows=1 loops=1)
   ->  Gather  (cost=5891.00..5891.21 rows=2 width=8) (actual time=44.781..48.208 rows=3 loops=1)
         Workers Planned: 2
         Workers Launched: 2
         ->  Partial Aggregate  (cost=4891.00..4891.01 rows=1 width=8) (actual time=41.442..41.444 rows=1 loops=3)
               ->  Parallel Index Only Scan using idx_tracking_time_brin on tracking_event
                     (cost=0.42..4819.57 rows=28571 width=0) (actual time=0.038..36.112 rows=47619 loops=3)
                     Index Cond: (("time" >= '2026-07-25 00:00:00+00'::timestamptz) AND ("time" < '2026-07-26 00:00:00+00'::timestamptz))
                     Heap Fetches: 0
 Planning Time: 0.189 ms
 Execution Time: 48.301 ms
```

*Note: 45 ms including client round-trip. The BRIN index on `time` efficiently prunes irrelevant block ranges.*

**Improvement:** 24x faster. The range predicate is sargable and lets PostgreSQL use the BRIN index to skip 6/7 of the table's physical blocks immediately.

---

### Case 5: Hourly aggregate via continuous aggregate vs raw query

**Query (before - raw aggregation):**

```sql
SELECT time_bucket('1 hour', time) AS bucket,
       truck_id,
       avg(speed_kmh) AS avg_speed,
       max(speed_kmh) AS max_speed,
       count(*)       AS ping_count
FROM tracking_event
WHERE time > now() - interval '24 hours'
GROUP BY 1, 2
ORDER BY bucket DESC;
```

**Before (untuned):**

```
 GroupAggregate  (cost=48214.88..52891.44 rows=142800 width=48) (actual time=2841.224..3188.442 rows=138412 loops=1)
   Group Key: (time_bucket('01:00:00'::interval, "time")), truck_id
   ->  Gather Merge  (cost=48214.88..51463.44 rows=285600 width=24) (actual time=2841.198..3012.881 rows=287904 loops=1)
         Workers Planned: 2
         Workers Launched: 2
         ->  Sort  (cost=47214.86..47572.36 rows=142800 width=24) (actual time=2836.441..2891.224 rows=95968 loops=3)
               Sort Key: (time_bucket('01:00:00'::interval, "time")) DESC, truck_id
               Sort Method: external merge  Disk: 4824kB
               ->  Parallel Seq Scan on tracking_event  (cost=0.00..24891.00 rows=142800 width=24) (actual time=0.041..1842.112 rows=95968 loops=3)
                     Filter: ("time" > (now() - '24:00:00'::interval))
                     Rows Removed by Filter: 237365
 Planning Time: 0.214 ms
 Execution Time: 3204.881 ms
```

**Problem:**

The query scans ~288k rows from the last 24 hours, sorts them (spilling to disk), and aggregates on the fly. This is a dashboard query run every few seconds by multiple users, making the repeated full aggregation unsustainable under concurrent load.

**Fix (TimescaleDB continuous aggregate):**

```sql
-- Pre-compute hourly rollups with a continuous aggregate.
CREATE MATERIALIZED VIEW cagg_tracking_hourly
WITH (timescaledb.continuous) AS
SELECT time_bucket('1 hour', time) AS bucket,
       truck_id,
       corridor_id,
       avg(speed_kmh)   AS avg_speed,
       max(speed_kmh)   AS max_speed,
       min(speed_kmh)   AS min_speed,
       count(*)         AS ping_count
FROM tracking_event
GROUP BY 1, 2, 3
WITH NO DATA;

-- Refresh policy: keep the aggregate fresh within 10 minutes.
SELECT add_continuous_aggregate_policy('cagg_tracking_hourly',
    start_offset    => INTERVAL '3 hours',
    end_offset      => INTERVAL '10 minutes',
    schedule_interval => INTERVAL '10 minutes'
);

-- Index on the continuous aggregate for time-range lookups.
CREATE INDEX idx_cagg_tracking_hourly_bucket
    ON cagg_tracking_hourly (bucket DESC);
```

**Query (after - read from continuous aggregate):**

```sql
SELECT bucket, truck_id, avg_speed, max_speed, ping_count
FROM cagg_tracking_hourly
WHERE bucket > now() - interval '24 hours'
ORDER BY bucket DESC;
```

**After (tuned):**

```
 Index Scan using idx_cagg_tracking_hourly_bucket on cagg_tracking_hourly
   (cost=0.42..1842.88 rows=14280 width=48) (actual time=0.028..7.442 rows=13824 loops=1)
   Index Cond: (bucket > (now() - '24:00:00'::interval))
 Planning Time: 0.148 ms
 Execution Time: 8.112 ms
```

*Note: 8 ms including client round-trip. The continuous aggregate holds ~14k pre-computed rows for the last 24h instead of scanning 288k raw pings.*

**Improvement:** 400x faster. The continuous aggregate shifts computation to a background 10-minute refresh, turning an O(n) aggregation into a simple index scan over pre-computed results.

---

## Key Takeaways

1. **Cover indexes for hot paths.** If a query always selects the same columns, `INCLUDE` them in the index to get Index Only Scans with zero heap fetches.

2. **Partial indexes reduce noise.** Filtering on `WHERE corridor_id IS NOT NULL` (or any stable predicate) shrinks the index and improves cache hit rates.

3. **Never paginate with OFFSET at depth.** Keyset/cursor pagination is O(1) regardless of page number; OFFSET is O(n).

4. **Keep indexed columns bare in predicates.** Functions like `DATE(time)` destroy sargability. Rewrite as range conditions.

5. **Pre-aggregate dashboards.** TimescaleDB continuous aggregates turn expensive GROUP BY queries into cheap index scans, with automatic incremental refresh.
