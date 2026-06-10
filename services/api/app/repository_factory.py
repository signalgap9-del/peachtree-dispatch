import os

from .dynamodb_repository import DynamoDBDeliveryRepository
from .repository import DeliveryRepository
from .repository_contract import DeliveryRepositoryContract


def create_repository() -> DeliveryRepositoryContract:
    table_name = os.getenv("DYNAMODB_TABLE")
    if table_name:
        return DynamoDBDeliveryRepository(
            table_name=table_name,
            endpoint_url=os.getenv("DYNAMODB_ENDPOINT_URL"),
        )
    return DeliveryRepository(os.getenv("DATABASE_PATH", "peachtree.db"))
