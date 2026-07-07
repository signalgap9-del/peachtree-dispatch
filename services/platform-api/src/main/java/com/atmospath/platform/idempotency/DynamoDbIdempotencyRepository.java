package com.atmospath.platform.idempotency;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

@Repository
@ConditionalOnProperty(name = "atmospath.idempotency-store", havingValue = "dynamodb")
public class DynamoDbIdempotencyRepository implements IdempotencyRepository {
    private static final Duration REQUEST_TTL = Duration.ofHours(24);

    private final DynamoDbClient client;
    private final String tableName;

    @Autowired
    public DynamoDbIdempotencyRepository(DynamoDbClient client, @Value("${atmospath.dynamodb-table}") String tableName) {
        this.client = client;
        this.tableName = tableName;
    }

    @Override
    public Optional<UUID> findResourceId(UUID tenantId, String operation, String keyHash) {
        var response = client.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(key(tenantId, operation, keyHash))
                .consistentRead(true)
                .projectionExpression("resourceId")
                .build());
        if (!response.hasItem() || response.item().get("resourceId") == null) {
            return Optional.empty();
        }
        return Optional.of(UUID.fromString(response.item().get("resourceId").s()));
    }

    @Override
    public void saveResourceId(UUID tenantId, String operation, String keyHash, UUID resourceId) {
        var now = Instant.now();
        var item = Map.of(
                "PK", string(partitionKey(tenantId)),
                "SK", string(sortKey(operation, keyHash)),
                "entityType", string("IdempotencyRecord"),
                "tenantId", string(tenantId.toString()),
                "operation", string(operation),
                "keyHash", string(keyHash),
                "resourceId", string(resourceId.toString()),
                "createdAt", string(now.toString()),
                "expiresAt", number(now.plus(REQUEST_TTL).getEpochSecond()));
        try {
            client.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .conditionExpression("attribute_not_exists(PK)")
                    .build());
        } catch (ConditionalCheckFailedException ignored) {
            // A retried request or concurrent first writer already recorded the resource.
        }
    }

    private static Map<String, AttributeValue> key(UUID tenantId, String operation, String keyHash) {
        return Map.of(
                "PK", string(partitionKey(tenantId)),
                "SK", string(sortKey(operation, keyHash)));
    }

    private static String partitionKey(UUID tenantId) {
        return "TENANT#" + tenantId;
    }

    private static String sortKey(String operation, String keyHash) {
        return "IDEMPOTENCY#" + operation + "#" + keyHash;
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(Number value) {
        return AttributeValue.builder().n(value.toString()).build();
    }
}
