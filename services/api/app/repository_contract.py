from datetime import datetime
from typing import Protocol

from .models import CreateDelivery, Delivery, DeliveryStatus, DeliverySummary


class DuplicateEventError(Exception):
    pass


class DeliveryRepositoryContract(Protocol):
    def list(
        self,
        status: DeliveryStatus | None = None,
        driver_id: str | None = None,
        promised_date: str | None = None,
    ) -> list[DeliverySummary]: ...

    def get(self, delivery_id: str) -> Delivery | None: ...

    def create(self, delivery_id: str, command: CreateDelivery) -> Delivery: ...

    def transition(
        self,
        delivery_id: str,
        event_id: str,
        target: DeliveryStatus,
        source: str,
        occurred_at: datetime | None = None,
        driver_id: str | None = None,
    ) -> Delivery: ...

    def seed(self) -> None: ...
