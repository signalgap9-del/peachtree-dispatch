package com.atmospath.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

class DynamoDbIdempotencyRepositoryTests {
    private final DynamoDbClient client = mock(DynamoDbClient.class);
    private final DynamoDbIdempotencyRepository repository = new DynamoDbIdempotencyRepository(client, "atmospath-dev");

    @Test
    void writesTenantScopedResourceRecordWithTtl() {
        var tenantId = UUID.randomUUID();
        var resourceId = UUID.randomUUID();

        repository.saveResourceId(tenantId, "saved-route:create", "abc123", resourceId);

        var captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(client).putItem(captor.capture());
        var request = captor.getValue();
        assertThat(request.tableName()).isEqualTo("atmospath-dev");
        assertThat(request.item().get("PK").s()).isEqualTo("TENANT#" + tenantId);
        assertThat(request.item().get("SK").s()).isEqualTo("IDEMPOTENCY#saved-route:create#abc123");
        assertThat(request.item().get("resourceId").s()).isEqualTo(resourceId.toString());
        assertThat(request.item().get("expiresAt").n()).isNotBlank();
        assertThat(request.conditionExpression()).isEqualTo("attribute_not_exists(PK)");
    }

    @Test
    void readsExistingResourceWithConsistentRead() {
        var tenantId = UUID.randomUUID();
        var resourceId = UUID.randomUUID();
        when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder()
                .item(Map.of("resourceId", AttributeValue.builder().s(resourceId.toString()).build()))
                .build());

        assertThat(repository.findResourceId(tenantId, "saved-route:create", "abc123")).contains(resourceId);

        var captor = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(client).getItem(captor.capture());
        assertThat(captor.getValue().consistentRead()).isTrue();
        assertThat(captor.getValue().key().get("PK").s()).isEqualTo("TENANT#" + tenantId);
        assertThat(captor.getValue().key().get("SK").s()).isEqualTo("IDEMPOTENCY#saved-route:create#abc123");
    }
}
