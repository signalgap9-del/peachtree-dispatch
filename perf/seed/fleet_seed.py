#!/usr/bin/env python3
"""Fleet telemetry seed data generator for local performance testing.

Generates realistic truck GPS pings along 20 major US freight corridors and
inserts them into PostgreSQL/TimescaleDB using fast batch inserts.

Usage:
    python fleet_seed.py --db-url postgresql://atmospath:devpassword@localhost:5432/atmospath --scale 1.0

At scale 1.0 this produces:
    - 10,000 trucks
    - 1,000,000 tracking_event rows (100 pings per truck over 7 days)
    - 1,000 carrier_profile rows
    - 500 freight_load rows

Requirements:
    pip install psycopg2-binary   (or psycopg[binary])
"""

from __future__ import annotations

import argparse
import random
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone

try:
    from psycopg2.extras import execute_values
    import psycopg2
except ImportError:
    try:
        from psycopg.extras import execute_values  # type: ignore[no-redef]
        import psycopg as psycopg2  # type: ignore[no-redef]
    except ImportError:
        print(
            "ERROR: Neither psycopg2 nor psycopg is installed.\n"
            "       pip install psycopg2-binary",
            file=sys.stderr,
        )
        sys.exit(1)

# ---------------------------------------------------------------------------
# Corridor definitions: approximate start/end lat/lon for 20 US interstates.
# ---------------------------------------------------------------------------
CORRIDORS: dict[str, tuple[tuple[float, float], tuple[float, float]]] = {
    "I-10": ((34.05, -118.24), (30.27, -81.66)),    # Los Angeles -> Jacksonville
    "I-20": ((32.78, -96.80), (33.52, -86.81)),     # Dallas -> Birmingham
    "I-40": ((35.22, -80.84), (34.05, -118.24)),    # Charlotte -> Los Angeles
    "I-70": ((39.96, -83.00), (39.74, -104.99)),    # Columbus -> Denver
    "I-80": ((40.71, -74.01), (37.77, -122.42)),    # New York -> San Francisco
    "I-90": ((42.36, -71.06), (47.61, -122.33)),    # Boston -> Seattle
    "I-95": ((25.76, -80.19), (42.36, -71.06)),     # Miami -> Boston
    "I-5":  ((32.72, -117.16), (47.61, -122.33)),   # San Diego -> Seattle
    "I-15": ((32.72, -117.16), (48.75, -114.00)),   # San Diego -> Sweetgrass MT
    "I-35": ((29.42, -98.49), (44.98, -93.27)),     # San Antonio -> Minneapolis
    "I-55": ((29.95, -90.07), (41.88, -87.63)),     # New Orleans -> Chicago
    "I-65": ((30.69, -88.04), (41.88, -87.63)),     # Mobile -> Chicago
    "I-75": ((25.76, -80.19), (42.33, -83.05)),     # Miami -> Detroit
    "I-85": ((33.75, -84.39), (36.16, -79.79)),     # Atlanta -> Greensboro
    "I-4":  ((27.95, -82.46), (28.54, -81.38)),     # Tampa -> Orlando
    "I-25": ((31.76, -106.49), (40.01, -105.27)),   # El Paso -> Denver
    "I-29": ((39.10, -94.58), (43.54, -96.73)),     # Kansas City -> Sioux Falls
    "I-44": ((36.15, -95.99), (38.63, -90.20)),     # Tulsa -> St. Louis
    "I-64": ((36.85, -75.98), (38.63, -90.20)),     # Virginia Beach -> St. Louis
    "I-77": ((33.75, -84.39), (41.50, -81.69)),     # Atlanta -> Cleveland
}

CORRIDOR_NAMES = list(CORRIDORS.keys())

US_STATES = [
    "AL", "AZ", "AR", "CA", "CO", "CT", "FL", "GA", "ID", "IL",
    "IN", "IA", "KS", "KY", "LA", "MD", "MA", "MI", "MN", "MO",
    "MS", "MT", "NE", "NV", "NM", "NY", "NC", "ND", "OH", "OK",
    "OR", "PA", "SC", "SD", "TN", "TX", "UT", "VA", "WA", "WI",
]


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def lerp(a: float, b: float, t: float) -> float:
    """Linear interpolation between a and b."""
    return a + (b - a) * t


def jitter(value: float, magnitude: float) -> float:
    """Add small random noise to simulate GPS drift."""
    return value + random.uniform(-magnitude, magnitude)


def generate_trucks(n: int) -> list[tuple]:
    """Generate n truck records: (id, corridor_id, home_state, created_at)."""
    now = datetime.now(timezone.utc)
    trucks = []
    for _ in range(n):
        truck_id = uuid.uuid4()
        corridor = random.choice(CORRIDOR_NAMES)
        state = random.choice(US_STATES)
        created = now - timedelta(days=random.randint(30, 365))
        trucks.append((truck_id, corridor, state, created))
    return trucks


def generate_tracking_events(
    trucks: list[tuple],
    pings_per_truck: int,
    spread_days: int = 7,
) -> list[tuple]:
    """Generate GPS pings for all trucks, spread over spread_days.

    Each ping: (id, truck_id, time, lat, lon, speed_kmh, heading, corridor_id)
    Positions are interpolated along the truck's assigned corridor with jitter.
    """
    now = datetime.now(timezone.utc)
    start = now - timedelta(days=spread_days)
    interval = timedelta(days=spread_days) / pings_per_truck

    events: list[tuple] = []
    for truck_id, corridor, _state, _created in trucks:
        (lat_a, lon_a), (lat_b, lon_b) = CORRIDORS[corridor]
        # Each truck starts at a random offset along the corridor
        base_t = random.random()
        direction = random.choice([1.0, -1.0])

        for i in range(pings_per_truck):
            # Progress along corridor (wraps at endpoints)
            progress = (base_t + direction * i / pings_per_truck) % 1.0
            lat = jitter(lerp(lat_a, lat_b, progress), 0.02)
            lon = jitter(lerp(lon_a, lon_b, progress), 0.02)
            speed = round(random.uniform(0, 120), 1)
            heading = random.randint(0, 359)
            ts = start + interval * i + timedelta(seconds=random.uniform(-30, 30))
            events.append((
                uuid.uuid4(), truck_id, ts,
                round(lat, 6), round(lon, 6),
                speed, heading, corridor,
            ))

    return events


def generate_carriers(n: int) -> list[tuple]:
    """Generate carrier_profile rows: (id, name, mc_number, state, rating, created_at)."""
    now = datetime.now(timezone.utc)
    carriers = []
    for i in range(n):
        carrier_id = uuid.uuid4()
        name = f"Carrier-{i:05d} LLC"
        mc_number = f"MC-{random.randint(100000, 999999)}"
        state = random.choice(US_STATES)
        rating = round(random.uniform(3.0, 5.0), 2)
        created = now - timedelta(days=random.randint(90, 1000))
        carriers.append((carrier_id, name, mc_number, state, rating, created))
    return carriers


def generate_freight_loads(n: int, trucks: list[tuple]) -> list[tuple]:
    """Generate freight_load rows: (id, truck_id, origin, destination, status, weight_kg, created_at)."""
    now = datetime.now(timezone.utc)
    statuses = ["PENDING", "IN_TRANSIT", "DELIVERED", "CANCELLED"]
    loads = []
    for _ in range(n):
        load_id = uuid.uuid4()
        truck_id = random.choice(trucks)[0]
        origin = f"{random.choice(US_STATES)}-Hub"
        destination = f"{random.choice(US_STATES)}-Hub"
        status = random.choices(statuses, weights=[15, 30, 50, 5])[0]
        weight = round(random.uniform(500, 36000), 1)
        created = now - timedelta(days=random.randint(1, 60))
        loads.append((load_id, truck_id, origin, destination, status, weight, created))
    return loads


# ---------------------------------------------------------------------------
# Database operations
# ---------------------------------------------------------------------------

DDL_STATEMENTS = """
-- Fleet telemetry tables (Phase 2). Idempotent: IF NOT EXISTS everywhere.

CREATE TABLE IF NOT EXISTS truck (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    corridor_id TEXT NOT NULL,
    home_state  TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS tracking_event (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    truck_id    UUID NOT NULL REFERENCES truck(id),
    time        TIMESTAMPTZ NOT NULL DEFAULT now(),
    lat         DOUBLE PRECISION NOT NULL CHECK (lat BETWEEN -90 AND 90),
    lon         DOUBLE PRECISION NOT NULL CHECK (lon BETWEEN -180 AND 180),
    speed_kmh   DOUBLE PRECISION NOT NULL CHECK (speed_kmh >= 0),
    heading     SMALLINT NOT NULL CHECK (heading BETWEEN 0 AND 359),
    corridor_id TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS carrier_profile (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        TEXT NOT NULL,
    mc_number   TEXT NOT NULL UNIQUE,
    state       TEXT NOT NULL,
    rating      DOUBLE PRECISION CHECK (rating BETWEEN 1.0 AND 5.0),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS freight_load (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    truck_id    UUID NOT NULL REFERENCES truck(id),
    origin      TEXT NOT NULL,
    destination TEXT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING','IN_TRANSIT','DELIVERED','CANCELLED')),
    weight_kg   DOUBLE PRECISION NOT NULL CHECK (weight_kg > 0),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Indexes for common query patterns
CREATE INDEX IF NOT EXISTS idx_tracking_truck_time
    ON tracking_event (truck_id, time DESC);

CREATE INDEX IF NOT EXISTS idx_tracking_corridor_time
    ON tracking_event (corridor_id, time DESC)
    WHERE corridor_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_tracking_time_brin
    ON tracking_event USING BRIN (time);
"""

BATCH_SIZE = 10_000
PROGRESS_INTERVAL = 100_000


def batch_insert(
    cur,
    table: str,
    columns: str,
    rows: list[tuple],
    template: str | None = None,
) -> None:
    """Insert rows in batches of BATCH_SIZE with progress reporting."""
    total = len(rows)
    inserted = 0
    for start in range(0, total, BATCH_SIZE):
        batch = rows[start : start + BATCH_SIZE]
        execute_values(
            cur,
            f"INSERT INTO {table} ({columns}) VALUES %s",
            batch,
            template=template,
            page_size=BATCH_SIZE,
        )
        inserted += len(batch)
        if inserted % PROGRESS_INTERVAL == 0 or inserted == total:
            pct = inserted / total * 100
            print(f"  {table}: {inserted:,}/{total:,} ({pct:.1f}%)")


def seed(db_url: str, scale: float) -> None:
    """Main seeding routine."""
    n_trucks = int(10_000 * scale)
    pings_per_truck = 100
    n_carriers = int(1_000 * scale)
    n_loads = int(500 * scale)

    print("Fleet Telemetry Seed Generator")
    print(f"  Scale:        {scale:.2f}x")
    print(f"  Trucks:       {n_trucks:,}")
    print(f"  Pings/truck:  {pings_per_truck}")
    print(f"  Total events: {n_trucks * pings_per_truck:,}")
    print(f"  Carriers:     {n_carriers:,}")
    print(f"  Freight loads:{n_loads:,}")
    print()

    t0 = time.perf_counter()

    # --- Generate data in memory ---
    print("[1/5] Generating truck records...")
    trucks = generate_trucks(n_trucks)

    print("[2/5] Generating tracking events (this may take a moment)...")
    events = generate_tracking_events(trucks, pings_per_truck)

    print("[3/5] Generating carrier profiles...")
    carriers = generate_carriers(n_carriers)

    print("[4/5] Generating freight loads...")
    loads = generate_freight_loads(n_loads, trucks)

    gen_elapsed = time.perf_counter() - t0
    print(f"      Data generation took {gen_elapsed:.1f}s")
    print()

    # --- Connect and insert ---
    print("[5/5] Inserting into database...")
    conn = psycopg2.connect(db_url)
    conn.autocommit = False
    try:
        cur = conn.cursor()

        # Create schema
        cur.execute(DDL_STATEMENTS)
        conn.commit()
        print("  Schema ensured (DDL applied).")

        # Insert trucks
        batch_insert(
            cur, "truck",
            "id, corridor_id, home_state, created_at",
            trucks,
        )
        conn.commit()

        # Insert tracking events (the big one)
        batch_insert(
            cur, "tracking_event",
            "id, truck_id, time, lat, lon, speed_kmh, heading, corridor_id",
            events,
        )
        conn.commit()

        # Insert carriers
        batch_insert(
            cur, "carrier_profile",
            "id, name, mc_number, state, rating, created_at",
            carriers,
        )
        conn.commit()

        # Insert freight loads
        batch_insert(
            cur, "freight_load",
            "id, truck_id, origin, destination, status, weight_kg, created_at",
            loads,
        )
        conn.commit()

    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()

    # --- Summary ---
    total_rows = n_trucks + len(events) + n_carriers + n_loads
    elapsed = time.perf_counter() - t0
    rows_per_sec = total_rows / elapsed if elapsed > 0 else 0

    print()
    print("=" * 60)
    print("SEED COMPLETE")
    print("=" * 60)
    print(f"  truck:           {n_trucks:>12,} rows")
    print(f"  tracking_event:  {len(events):>12,} rows")
    print(f"  carrier_profile: {n_carriers:>12,} rows")
    print(f"  freight_load:    {n_loads:>12,} rows")
    print(f"  {'---' * 12}")
    print(f"  TOTAL:           {total_rows:>12,} rows")
    print(f"  Elapsed:         {elapsed:>12.1f}s")
    print(f"  Throughput:      {rows_per_sec:>12,.0f} rows/sec")
    print("=" * 60)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Generate fleet telemetry seed data for local performance testing.",
    )
    parser.add_argument(
        "--db-url",
        default="postgresql://atmospath:devpassword@localhost:5432/atmospath",
        help="PostgreSQL connection string (default: local dev database)",
    )
    parser.add_argument(
        "--scale",
        type=float,
        default=1.0,
        help="Scale factor: 1.0 = 10k trucks / 1M events (default: 1.0)",
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=None,
        help="Random seed for reproducibility",
    )
    args = parser.parse_args()

    if args.seed is not None:
        random.seed(args.seed)

    if args.scale <= 0:
        parser.error("--scale must be positive")

    seed(args.db_url, args.scale)


if __name__ == "__main__":
    main()
