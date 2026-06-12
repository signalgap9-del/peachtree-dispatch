package com.atmospath.platform.relational;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

class DynamoDbSavedPlaceRepositoryTests {
    private final DynamoDbClient client = mock(DynamoDbClient.class);
    private final DynamoDbSavedPlaceRepository repository = new DynamoDbSavedPlaceRepository(client, "atmospath-dev");

    @Test
    void writesSavedPlaceUsingUserPartition() {
        var place = new SavedPlace(UUID.randomUUID(), UUID.randomUUID(), "Atlanta", -84.388, 33.749, 42);

        repository.save(place);

        var captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(client).putItem(captor.capture());
        assertThat(captor.getValue().tableName()).isEqualTo("atmospath-dev");
        assertThat(captor.getValue().item().get("PK").s()).isEqualTo("USER#" + place.userId());
        assertThat(captor.getValue().item().get("SK").s()).isEqualTo("SAVED_PLACE#" + place.savedItemId());
    }

    @Test
    void queriesOnlySavedPlacesAndMapsResults() {
        var savedItemId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        when(client.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder()
                .items(List.of(Map.of(
                        "savedItemId", string(savedItemId.toString()),
                        "userId", string(userId.toString()),
                        "name", string("Atlanta"),
                        "longitude", number("-84.388"),
                        "latitude", number("33.749"),
                        "currentRiskScore", number("42"))))
                .build());

        assertThat(repository.findAll(userId))
                .containsExactly(new SavedPlace(savedItemId, userId, "Atlanta", -84.388, 33.749, 42));

        var captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client).query(captor.capture());
        assertThat(captor.getValue().keyConditionExpression()).contains("begins_with");
    }

    @Test
    void deletesOnlyTheOwnedSavedPlaceKey() {
        var userId = UUID.randomUUID();
        var savedItemId = UUID.randomUUID();

        repository.delete(userId, savedItemId);

        var captor = ArgumentCaptor.forClass(DeleteItemRequest.class);
        verify(client).deleteItem(captor.capture());
        assertThat(captor.getValue().key().get("PK").s()).isEqualTo("USER#" + userId);
        assertThat(captor.getValue().key().get("SK").s()).isEqualTo("SAVED_PLACE#" + savedItemId);
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(String value) {
        return AttributeValue.builder().n(value).build();
    }
}
