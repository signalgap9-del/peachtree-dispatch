"""Tests for the SQLite DeliveryRepository (app.repository).

Each test gets an isolated temporary database so no shared state leaks.
"""
from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest

from app.models import CreateDelivery, DeliveryStatus, Location
from app.repository import DeliveryRepository
from app.repository_contract import DuplicateEventError


@pytest.fixture()
def repo(tmp_path) -> DeliveryRepository:
    return DeliveryRepository(str(tmp_path / "deliveries.db"))


def _command(driver_id: str | None = None, hours: int = 6) -> CreateDelivery:
    return CreateDelivery(
        origin=Location(city="Atlanta", state="GA"),
        destination=Location(city="Savannah", state="GA"),
        promised_at=datetime.now(UTC) + timedelta(hours=hours),
        driver_id=driver_id,
    )


def test_create_without_driver_starts_created(repo) -> None:
    delivery = repo.create("PD-1", _command())
    assert delivery.status == DeliveryStatus.CREATED
    assert delivery.version == 1
    assert len(delivery.events) == 1


def test_create_with_driver_starts_assigned(repo) -> None:
    delivery = repo.create("PD-2", _command(driver_id="driver-1"))
    assert delivery.status == DeliveryStatus.ASSIGNED
    assert delivery.driver_id == "driver-1"


def test_get_missing_returns_none(repo) -> None:
    assert repo.get("does-not-exist") is None


def test_transition_advances_status_and_version(repo) -> None:
    repo.create("PD-3", _command())  # CREATED
    delivery = repo.transition("PD-3", "evt-1", DeliveryStatus.ASSIGNED, "test")
    assert delivery.status == DeliveryStatus.ASSIGNED
    assert delivery.version == 2


def test_transition_records_event_history(repo) -> None:
    repo.create("PD-4", _command())
    repo.transition("PD-4", "evt-a", DeliveryStatus.ASSIGNED, "test")
    repo.transition("PD-4", "evt-b", DeliveryStatus.PICKED_UP, "test")

    delivery = repo.get("PD-4")
    assert delivery is not None
    assert delivery.status == DeliveryStatus.PICKED_UP
    # created + two transitions
    assert len(delivery.events) == 3


def test_transition_duplicate_event_raises(repo) -> None:
    repo.create("PD-5", _command())
    repo.transition("PD-5", "evt-dup", DeliveryStatus.ASSIGNED, "test")

    with pytest.raises(DuplicateEventError):
        repo.transition("PD-5", "evt-dup", DeliveryStatus.PICKED_UP, "test")


def test_transition_missing_delivery_raises(repo) -> None:
    with pytest.raises(KeyError):
        repo.transition("ghost", "evt-x", DeliveryStatus.ASSIGNED, "test")


def test_transition_invalid_transition_raises(repo) -> None:
    repo.create("PD-6", _command())  # CREATED
    with pytest.raises(ValueError, match="Invalid transition"):
        repo.transition("PD-6", "evt-bad", DeliveryStatus.DELIVERED, "test")


def test_transition_can_override_driver(repo) -> None:
    repo.create("PD-7", _command())
    delivery = repo.transition("PD-7", "evt-a", DeliveryStatus.ASSIGNED, "test", driver_id="new-driver")
    assert delivery.driver_id == "new-driver"


def test_list_filters_by_status_driver_and_date(repo) -> None:
    repo.create("PD-8", _command(driver_id="driver-1"))  # ASSIGNED
    repo.create("PD-9", _command())  # CREATED

    assert len(repo.list()) == 2
    assert len(repo.list(status=DeliveryStatus.ASSIGNED)) == 1
    assert len(repo.list(status=DeliveryStatus.CREATED)) == 1
    assert len(repo.list(driver_id="driver-1")) == 1
    assert len(repo.list(driver_id="nobody")) == 0


def test_list_filters_by_promised_date(repo) -> None:
    repo.create("PD-10", _command(hours=6))
    promised_date = (datetime.now(UTC) + timedelta(hours=6)).strftime("%Y-%m-%d")

    assert len(repo.list(promised_date=promised_date)) == 1
    assert len(repo.list(promised_date="1999-01-01")) == 0


def test_seed_populates_once_and_is_idempotent(repo) -> None:
    repo.seed()
    assert len(repo.list()) == 5

    repo.seed()  # already seeded -> no-op
    assert len(repo.list()) == 5


def test_seed_covers_expected_statuses(repo) -> None:
    repo.seed()
    statuses = {summary.status for summary in repo.list()}
    assert DeliveryStatus.IN_TRANSIT in statuses
    assert DeliveryStatus.DELIVERED in statuses
    assert DeliveryStatus.FAILED in statuses
