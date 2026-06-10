import json
import sqlite3
from datetime import UTC, datetime
from pathlib import Path
from threading import Lock

from .domain import validate_transition
from .models import (
    CreateDelivery,
    Delivery,
    DeliveryEvent,
    DeliveryStatus,
    DeliverySummary,
    Location,
)
from .repository_contract import DuplicateEventError


class DeliveryRepository:
    def __init__(self, database_path: str) -> None:
        Path(database_path).parent.mkdir(parents=True, exist_ok=True)
        self.database_path = database_path
        self.lock = Lock()
        self._initialize()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.database_path, check_same_thread=False)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        return connection

    def _initialize(self) -> None:
        with self._connect() as connection:
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS deliveries (
                    delivery_id TEXT PRIMARY KEY,
                    organization_id TEXT NOT NULL,
                    status TEXT NOT NULL,
                    driver_id TEXT,
                    origin TEXT NOT NULL,
                    destination TEXT NOT NULL,
                    promised_at TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    version INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS delivery_events (
                    event_id TEXT PRIMARY KEY,
                    delivery_id TEXT NOT NULL REFERENCES deliveries(delivery_id),
                    event_type TEXT NOT NULL,
                    from_status TEXT,
                    to_status TEXT NOT NULL,
                    source TEXT NOT NULL,
                    occurred_at TEXT NOT NULL
                );
                """
            )

    @staticmethod
    def _summary(row: sqlite3.Row) -> DeliverySummary:
        return DeliverySummary(
            delivery_id=row["delivery_id"],
            status=row["status"],
            driver_id=row["driver_id"],
            origin=Location.model_validate_json(row["origin"]),
            destination=Location.model_validate_json(row["destination"]),
            promised_at=row["promised_at"],
            updated_at=row["updated_at"],
            version=row["version"],
        )

    def list(
        self,
        status: DeliveryStatus | None = None,
        driver_id: str | None = None,
        promised_date: str | None = None,
    ) -> list[DeliverySummary]:
        clauses: list[str] = []
        parameters: list[str] = []
        if status:
            clauses.append("status = ?")
            parameters.append(status)
        if driver_id:
            clauses.append("driver_id = ?")
            parameters.append(driver_id)
        if promised_date:
            clauses.append("substr(promised_at, 1, 10) = ?")
            parameters.append(promised_date)
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        with self._connect() as connection:
            rows = connection.execute(
                f"SELECT * FROM deliveries {where} ORDER BY updated_at DESC", parameters
            ).fetchall()
        return [self._summary(row) for row in rows]

    def get(self, delivery_id: str) -> Delivery | None:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT * FROM deliveries WHERE delivery_id = ?", (delivery_id,)
            ).fetchone()
            if not row:
                return None
            event_rows = connection.execute(
                "SELECT * FROM delivery_events WHERE delivery_id = ? ORDER BY occurred_at",
                (delivery_id,),
            ).fetchall()
        return Delivery(
            **self._summary(row).model_dump(),
            organization_id=row["organization_id"],
            created_at=row["created_at"],
            events=[DeliveryEvent(**dict(event_row)) for event_row in event_rows],
        )

    def create(self, delivery_id: str, command: CreateDelivery) -> Delivery:
        now = datetime.now(UTC).isoformat()
        status = DeliveryStatus.ASSIGNED if command.driver_id else DeliveryStatus.CREATED
        event_id = f"evt-{delivery_id}-created"
        with self.lock, self._connect() as connection:
            connection.execute(
                """
                INSERT INTO deliveries VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    delivery_id,
                    "atlanta-ops",
                    status,
                    command.driver_id,
                    command.origin.model_dump_json(),
                    command.destination.model_dump_json(),
                    command.promised_at.isoformat(),
                    now,
                    now,
                    1,
                ),
            )
            connection.execute(
                "INSERT INTO delivery_events VALUES (?, ?, ?, ?, ?, ?, ?)",
                (event_id, delivery_id, "DeliveryCreated", None, status, "command-api", now),
            )
        return self.get(delivery_id)  # type: ignore[return-value]

    def transition(
        self,
        delivery_id: str,
        event_id: str,
        target: DeliveryStatus,
        source: str,
        occurred_at: datetime | None = None,
        driver_id: str | None = None,
    ) -> Delivery:
        now = (occurred_at or datetime.now(UTC)).isoformat()
        with self.lock, self._connect() as connection:
            if connection.execute(
                "SELECT 1 FROM delivery_events WHERE event_id = ?", (event_id,)
            ).fetchone():
                raise DuplicateEventError(event_id)
            row = connection.execute(
                "SELECT status, version, driver_id FROM deliveries WHERE delivery_id = ?",
                (delivery_id,),
            ).fetchone()
            if not row:
                raise KeyError(delivery_id)
            current = DeliveryStatus(row["status"])
            validate_transition(current, target)
            assigned_driver = driver_id if driver_id is not None else row["driver_id"]
            connection.execute(
                """
                UPDATE deliveries
                SET status = ?, driver_id = ?, updated_at = ?, version = version + 1
                WHERE delivery_id = ? AND version = ?
                """,
                (target, assigned_driver, now, delivery_id, row["version"]),
            )
            connection.execute(
                "INSERT INTO delivery_events VALUES (?, ?, ?, ?, ?, ?, ?)",
                (
                    event_id,
                    delivery_id,
                    "DeliveryStatusChanged",
                    current,
                    target,
                    source,
                    now,
                ),
            )
        return self.get(delivery_id)  # type: ignore[return-value]

    def seed(self) -> None:
        if self.list():
            return
        samples = [
            ("Atlanta", "GA", "Savannah", "GA", "driver-42", DeliveryStatus.IN_TRANSIT),
            ("Marietta", "GA", "Athens", "GA", "driver-17", DeliveryStatus.PICKED_UP),
            ("Decatur", "GA", "Macon", "GA", None, DeliveryStatus.CREATED),
            ("Atlanta", "GA", "Augusta", "GA", "driver-42", DeliveryStatus.FAILED),
            ("Roswell", "GA", "Columbus", "GA", "driver-09", DeliveryStatus.DELIVERED),
        ]
        from datetime import timedelta
        from uuid import uuid4

        for index, (oc, os, dc, ds, driver, final_status) in enumerate(samples):
            delivery_id = f"PD-{1001 + index}"
            command = CreateDelivery(
                origin=Location(city=oc, state=os),
                destination=Location(city=dc, state=ds),
                promised_at=datetime.now(UTC) + timedelta(hours=6 + index * 3),
                driver_id=driver,
            )
            delivery = self.create(delivery_id, command)
            current = delivery.status
            path = {
                DeliveryStatus.PICKED_UP: [DeliveryStatus.PICKED_UP],
                DeliveryStatus.IN_TRANSIT: [DeliveryStatus.PICKED_UP, DeliveryStatus.IN_TRANSIT],
                DeliveryStatus.FAILED: [DeliveryStatus.PICKED_UP, DeliveryStatus.FAILED],
                DeliveryStatus.DELIVERED: [
                    DeliveryStatus.PICKED_UP,
                    DeliveryStatus.IN_TRANSIT,
                    DeliveryStatus.DELIVERED,
                ],
            }.get(final_status, [])
            for target in path:
                if target != current:
                    self.transition(delivery_id, f"evt-{uuid4()}", target, "seed-data")
                    current = target
