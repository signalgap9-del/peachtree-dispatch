from datetime import UTC, datetime, timedelta

import boto3
from boto3.dynamodb.types import TypeDeserializer, TypeSerializer
from botocore.exceptions import ClientError

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


class DynamoDBDeliveryRepository:
    organization_id = "atlanta-ops"

    def __init__(
        self,
        table_name: str,
        endpoint_url: str | None = None,
        region_name: str = "us-east-1",
    ) -> None:
        self.table_name = table_name
        self.client = boto3.client(
            "dynamodb", endpoint_url=endpoint_url, region_name=region_name
        )
        self.serializer = TypeSerializer()
        self.deserializer = TypeDeserializer()

    def _delivery_pk(self, delivery_id: str) -> str:
        return f"ORG#{self.organization_id}#DELIVERY#{delivery_id}"

    def _serialize(self, item: dict) -> dict:
        return {key: self.serializer.serialize(value) for key, value in item.items()}

    def _deserialize(self, item: dict) -> dict:
        return {key: self.deserializer.deserialize(value) for key, value in item.items()}

    def _summary(self, item: dict) -> DeliverySummary:
        return DeliverySummary(
            delivery_id=item["deliveryId"],
            status=item["status"],
            driver_id=item.get("driverId"),
            origin=Location(**item["origin"]),
            destination=Location(**item["destination"]),
            promised_at=item["promisedAt"],
            updated_at=item["updatedAt"],
            version=item["version"],
        )

    def list(
        self,
        status: DeliveryStatus | None = None,
        driver_id: str | None = None,
        promised_date: str | None = None,
    ) -> list[DeliverySummary]:
        if status:
            response = self.client.query(
                TableName=self.table_name,
                IndexName="GSI1",
                KeyConditionExpression="GSI1PK = :pk",
                ExpressionAttributeValues=self._serialize(
                    {":pk": f"ORG#{self.organization_id}#STATUS#{status}"}
                ),
                ScanIndexForward=False,
            )
        elif driver_id:
            response = self.client.query(
                TableName=self.table_name,
                IndexName="GSI2",
                KeyConditionExpression="GSI2PK = :pk",
                ExpressionAttributeValues=self._serialize(
                    {":pk": f"ORG#{self.organization_id}#DRIVER#{driver_id}"}
                ),
            )
        elif promised_date:
            response = self.client.query(
                TableName=self.table_name,
                IndexName="GSI3",
                KeyConditionExpression="GSI3PK = :pk",
                ExpressionAttributeValues=self._serialize(
                    {":pk": f"ORG#{self.organization_id}#PROMISED_DATE#{promised_date}"}
                ),
            )
        else:
            response = self.client.query(
                TableName=self.table_name,
                IndexName="GSI4",
                KeyConditionExpression="GSI4PK = :pk",
                ExpressionAttributeValues=self._serialize(
                    {":pk": f"ORG#{self.organization_id}#DELIVERIES"}
                ),
                ScanIndexForward=False,
            )
        return [self._summary(self._deserialize(item)) for item in response["Items"]]

    def get(self, delivery_id: str) -> Delivery | None:
        response = self.client.query(
            TableName=self.table_name,
            KeyConditionExpression="PK = :pk",
            ExpressionAttributeValues=self._serialize(
                {":pk": self._delivery_pk(delivery_id)}
            ),
            ConsistentRead=True,
        )
        items = [self._deserialize(item) for item in response["Items"]]
        summary = next((item for item in items if item["SK"] == "META"), None)
        if not summary:
            return None
        events = [
            DeliveryEvent(
                event_id=item["eventId"],
                event_type=item["eventType"],
                from_status=item.get("fromStatus"),
                to_status=item["toStatus"],
                source=item["source"],
                occurred_at=item["occurredAt"],
            )
            for item in items
            if item["SK"].startswith("EVENT#")
        ]
        return Delivery(
            **self._summary(summary).model_dump(),
            organization_id=summary["organizationId"],
            created_at=summary["createdAt"],
            events=events,
        )

    def create(self, delivery_id: str, command: CreateDelivery) -> Delivery:
        now = datetime.now(UTC).isoformat()
        status = DeliveryStatus.ASSIGNED if command.driver_id else DeliveryStatus.CREATED
        meta = self._meta_item(delivery_id, command, status, now)
        event = self._event_item(
            delivery_id, f"evt-{delivery_id}-created", None, status, "command-api", now
        )
        try:
            self.client.transact_write_items(
                TransactItems=[
                    {
                        "Put": {
                            "TableName": self.table_name,
                            "Item": self._serialize(meta),
                            "ConditionExpression": "attribute_not_exists(PK)",
                        }
                    },
                    {"Put": {"TableName": self.table_name, "Item": self._serialize(event)}},
                ]
            )
        except ClientError as error:
            if error.response["Error"]["Code"] == "TransactionCanceledException":
                raise ValueError(f"Delivery {delivery_id} already exists") from error
            raise
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
        current_delivery = self.get(delivery_id)
        if not current_delivery:
            raise KeyError(delivery_id)
        if self._idempotency_exists(source, event_id):
            raise DuplicateEventError(event_id)
        validate_transition(current_delivery.status, target)
        now = (occurred_at or datetime.now(UTC)).isoformat()
        lock = {
            "PK": f"ORG#{self.organization_id}#IDEMPOTENCY#{source}#{event_id}",
            "SK": "LOCK",
            "entityType": "IdempotencyRecord",
            "deliveryId": delivery_id,
            "createdAt": now,
            "expiresAt": int((datetime.now(UTC) + timedelta(days=30)).timestamp()),
        }
        event = self._event_item(
            delivery_id, event_id, current_delivery.status, target, source, now
        )
        assigned_driver = driver_id if driver_id is not None else current_delivery.driver_id
        names = {"#status": "status", "#version": "version"}
        values = self._serialize(
            {
                ":status": str(target),
                ":driver": assigned_driver or "",
                ":updated": now,
                ":one": 1,
                ":expected": current_delivery.version,
                ":gsi1pk": f"ORG#{self.organization_id}#STATUS#{target}",
                ":gsi1sk": f"{now}#{delivery_id}",
                ":gsi4pk": f"ORG#{self.organization_id}#DELIVERIES",
                ":gsi4sk": f"{now}#{delivery_id}",
            }
        )
        update_expression = (
            "SET #status=:status, driverId=:driver, updatedAt=:updated, "
            "#version=#version+:one, GSI1PK=:gsi1pk, GSI1SK=:gsi1sk, "
            "GSI4PK=:gsi4pk, GSI4SK=:gsi4sk"
        )
        if assigned_driver:
            values.update(
                self._serialize(
                    {
                        ":gsi2pk": f"ORG#{self.organization_id}#DRIVER#{assigned_driver}",
                        ":gsi2sk": f"{current_delivery.promised_at.isoformat()}#{delivery_id}",
                    }
                )
            )
            update_expression += ", GSI2PK=:gsi2pk, GSI2SK=:gsi2sk"
        try:
            self.client.transact_write_items(
                TransactItems=[
                    {
                        "Put": {
                            "TableName": self.table_name,
                            "Item": self._serialize(lock),
                            "ConditionExpression": "attribute_not_exists(PK)",
                        }
                    },
                    {
                        "Update": {
                            "TableName": self.table_name,
                            "Key": self._serialize(
                                {"PK": self._delivery_pk(delivery_id), "SK": "META"}
                            ),
                            "UpdateExpression": update_expression,
                            "ConditionExpression": "#version=:expected",
                            "ExpressionAttributeNames": names,
                            "ExpressionAttributeValues": values,
                        }
                    },
                    {"Put": {"TableName": self.table_name, "Item": self._serialize(event)}},
                ]
            )
        except ClientError as error:
            if error.response["Error"]["Code"] == "TransactionCanceledException":
                if self._idempotency_exists(source, event_id):
                    raise DuplicateEventError(event_id) from error
                raise ValueError("Concurrent delivery update detected") from error
            raise
        return self.get(delivery_id)  # type: ignore[return-value]

    def seed(self) -> None:
        return

    def _idempotency_exists(self, source: str, event_id: str) -> bool:
        response = self.client.get_item(
            TableName=self.table_name,
            Key=self._serialize(
                {
                    "PK": f"ORG#{self.organization_id}#IDEMPOTENCY#{source}#{event_id}",
                    "SK": "LOCK",
                }
            ),
        )
        return "Item" in response

    def _meta_item(
        self,
        delivery_id: str,
        command: CreateDelivery,
        status: DeliveryStatus,
        now: str,
    ) -> dict:
        promised = command.promised_at.isoformat()
        item = {
            "PK": self._delivery_pk(delivery_id),
            "SK": "META",
            "entityType": "Delivery",
            "deliveryId": delivery_id,
            "organizationId": self.organization_id,
            "status": str(status),
            "origin": command.origin.model_dump(),
            "destination": command.destination.model_dump(),
            "promisedAt": promised,
            "createdAt": now,
            "updatedAt": now,
            "version": 1,
            "GSI1PK": f"ORG#{self.organization_id}#STATUS#{status}",
            "GSI1SK": f"{now}#{delivery_id}",
            "GSI3PK": f"ORG#{self.organization_id}#PROMISED_DATE#{promised[:10]}",
            "GSI3SK": f"{promised}#{delivery_id}",
            "GSI4PK": f"ORG#{self.organization_id}#DELIVERIES",
            "GSI4SK": f"{now}#{delivery_id}",
        }
        if command.driver_id:
            item["driverId"] = command.driver_id
            item["GSI2PK"] = f"ORG#{self.organization_id}#DRIVER#{command.driver_id}"
            item["GSI2SK"] = f"{promised}#{delivery_id}"
        return item

    def _event_item(
        self,
        delivery_id: str,
        event_id: str,
        previous: DeliveryStatus | None,
        target: DeliveryStatus,
        source: str,
        occurred_at: str,
    ) -> dict:
        item = {
            "PK": self._delivery_pk(delivery_id),
            "SK": f"EVENT#{occurred_at}#{event_id}",
            "entityType": "DeliveryEvent",
            "eventId": event_id,
            "eventType": "DeliveryStatusChanged" if previous else "DeliveryCreated",
            "toStatus": str(target),
            "source": source,
            "occurredAt": occurred_at,
        }
        if previous:
            item["fromStatus"] = str(previous)
        return item
