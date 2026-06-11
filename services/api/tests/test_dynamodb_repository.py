from datetime import UTC, datetime, timedelta

import boto3
import pytest
from moto import mock_aws

from app.dynamodb_repository import DynamoDBDeliveryRepository
from app.models import CreateDelivery, DeliveryStatus, Location
from app.repository_contract import DuplicateEventError


def create_table() -> None:
    client = boto3.client("dynamodb", region_name="us-east-1")
    client.create_table(
        TableName="test-deliveries",
        BillingMode="PAY_PER_REQUEST",
        KeySchema=[
            {"AttributeName": "PK", "KeyType": "HASH"},
            {"AttributeName": "SK", "KeyType": "RANGE"},
        ],
        AttributeDefinitions=[
            {"AttributeName": "PK", "AttributeType": "S"},
            {"AttributeName": "SK", "AttributeType": "S"},
            {"AttributeName": "GSI1PK", "AttributeType": "S"},
            {"AttributeName": "GSI1SK", "AttributeType": "S"},
            {"AttributeName": "GSI2PK", "AttributeType": "S"},
            {"AttributeName": "GSI2SK", "AttributeType": "S"},
            {"AttributeName": "GSI3PK", "AttributeType": "S"},
            {"AttributeName": "GSI3SK", "AttributeType": "S"},
            {"AttributeName": "GSI4PK", "AttributeType": "S"},
            {"AttributeName": "GSI4SK", "AttributeType": "S"},
        ],
        GlobalSecondaryIndexes=[
            {
                "IndexName": f"GSI{index}",
                "KeySchema": [
                    {"AttributeName": f"GSI{index}PK", "KeyType": "HASH"},
                    {"AttributeName": f"GSI{index}SK", "KeyType": "RANGE"},
                ],
                "Projection": {"ProjectionType": "ALL"},
            }
            for index in range(1, 5)
        ],
    )


@mock_aws
def test_dynamodb_repository_contract() -> None:
    create_table()
    repository = DynamoDBDeliveryRepository("test-deliveries")
    command = CreateDelivery(
        origin=Location(city="Atlanta", state="GA"),
        destination=Location(city="Athens", state="GA"),
        promised_at=datetime.now(UTC) + timedelta(hours=4),
        driver_id="driver-17",
    )

    created = repository.create("PD-CONTRACT", command)
    assert created.status == DeliveryStatus.ASSIGNED
    assert repository.get("PD-CONTRACT") is not None
    assert len(repository.list()) == 1
    assert len(repository.list(driver_id="driver-17")) == 1

    transitioned = repository.transition(
        "PD-CONTRACT", "evt-contract", DeliveryStatus.PICKED_UP, "contract-test"
    )
    assert transitioned.version == 2
    assert transitioned.events[-1].to_status == DeliveryStatus.PICKED_UP

    with pytest.raises(DuplicateEventError):
        repository.transition(
            "PD-CONTRACT", "evt-contract", DeliveryStatus.PICKED_UP, "contract-test"
        )
