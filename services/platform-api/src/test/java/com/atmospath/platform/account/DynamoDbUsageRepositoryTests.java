package com.atmospath.platform.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

class DynamoDbUsageRepositoryTests {
    private final DynamoDbClient client = mock(DynamoDbClient.class);
    private final DynamoDbUsageRepository repository = new DynamoDbUsageRepository(client, "atmospath-dev");

    @Test
    void incrementsTenantFeatureCounterAtomically() {
        var tenantId = UUID.randomUUID();
        var day = LocalDate.of(2026, 7, 4);
        when(client.updateItem(any(UpdateItemRequest.class))).thenReturn(UpdateItemResponse.builder()
                .attributes(Map.of("used", number("3")))
                .build());

        assertThat(repository.incrementAndGet(tenantId, MeteredFeature.ROUTE_PLAN, day)).isEqualTo(3);

        var captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(client).updateItem(captor.capture());
        var request = captor.getValue();
        assertThat(request.tableName()).isEqualTo("atmospath-dev");
        assertThat(request.key().get("PK").s()).isEqualTo("TENANT#" + tenantId);
        assertThat(request.key().get("SK").s()).isEqualTo("USAGE#2026-07-04#ROUTE_PLAN");
        assertThat(request.updateExpression()).contains("ADD #used :one", "expiresAt");
        assertThat(request.returnValues()).isEqualTo(ReturnValue.UPDATED_NEW);
    }

    @Test
    void readsCurrentUsageByTenantFeatureAndDay() {
        var tenantId = UUID.randomUUID();
        var day = LocalDate.of(2026, 7, 4);
        when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder()
                .item(Map.of("used", number("8")))
                .build());

        assertThat(repository.current(tenantId, MeteredFeature.PLACE_SEARCH, day)).isEqualTo(8);

        var captor = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(client).getItem(captor.capture());
        var request = captor.getValue();
        assertThat(request.consistentRead()).isTrue();
        assertThat(request.key().get("PK").s()).isEqualTo("TENANT#" + tenantId);
        assertThat(request.key().get("SK").s()).isEqualTo("USAGE#2026-07-04#PLACE_SEARCH");
        assertThat(request.projectionExpression()).isEqualTo("#used");
    }

    @Test
    void returnsZeroWhenCounterDoesNotExist() {
        when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());

        assertThat(repository.current(UUID.randomUUID(), MeteredFeature.ALERT_SEARCH, LocalDate.of(2026, 7, 4)))
                .isZero();
    }

    private static AttributeValue number(String value) {
        return AttributeValue.builder().n(value).build();
    }
}
