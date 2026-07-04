package com.atmospath.platform.account;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

@Repository
@ConditionalOnProperty(name = "atmospath.usage-store", havingValue = "dynamodb")
public class DynamoDbUsageRepository implements UsageRepository {
    private static final int COUNTER_TTL_DAYS = 35;

    private final DynamoDbClient client;
    private final String tableName;

    @Autowired
    public DynamoDbUsageRepository(DynamoDbClient client, @Value("${atmospath.dynamodb-table}") String tableName) {
        this.client = client;
        this.tableName = tableName;
    }

    @Override
    public int incrementAndGet(UUID tenantId, MeteredFeature feature, LocalDate day) {
        var response = client.updateItem(UpdateItemRequest.builder()
                .tableName(tableName)
                .key(key(tenantId, feature, day))
                .updateExpression("""
                        SET entityType = :entityType,
                            tenantId = :tenantId,
                            feature = :feature,
                            #usageDay = :usageDay,
                            updatedAt = :updatedAt,
                            expiresAt = :expiresAt
                        ADD #used :one
                        """)
                .expressionAttributeNames(Map.of(
                        "#used", "used",
                        "#usageDay", "day"))
                .expressionAttributeValues(Map.of(
                        ":entityType", string("TenantUsageCounter"),
                        ":tenantId", string(tenantId.toString()),
                        ":feature", string(feature.name()),
                        ":usageDay", string(day.toString()),
                        ":updatedAt", string(Instant.now().toString()),
                        ":expiresAt", number(expiresAt(day)),
                        ":one", number(1)))
                .returnValues(ReturnValue.UPDATED_NEW)
                .build());
        return intValue(response.attributes().get("used"));
    }

    @Override
    public int current(UUID tenantId, MeteredFeature feature, LocalDate day) {
        var response = client.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(key(tenantId, feature, day))
                .consistentRead(true)
                .projectionExpression("#used")
                .expressionAttributeNames(Map.of("#used", "used"))
                .build());
        if (!response.hasItem()) {
            return 0;
        }
        return intValue(response.item().get("used"));
    }

    private static Map<String, AttributeValue> key(UUID tenantId, MeteredFeature feature, LocalDate day) {
        return Map.of(
                "PK", string("TENANT#" + tenantId),
                "SK", string("USAGE#" + day + "#" + feature.name()));
    }

    private static long expiresAt(LocalDate day) {
        return day.plusDays(COUNTER_TTL_DAYS).atStartOfDay().toEpochSecond(ZoneOffset.UTC);
    }

    private static int intValue(AttributeValue value) {
        return value == null ? 0 : Integer.parseInt(value.n());
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(Number value) {
        return AttributeValue.builder().n(value.toString()).build();
    }
}
