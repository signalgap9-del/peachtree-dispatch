package com.atmospath.platform.relational;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
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
        assertThat(captor.getValue().conditionExpression()).contains("userId = :userId");
    }

    @Test
    void writesSavedRouteUsingUserPartition() {
        var route = savedRoute();

        repository.saveRoute(route);

        var captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(client).putItem(captor.capture());
        assertThat(captor.getValue().item().get("PK").s()).isEqualTo("USER#" + route.userId());
        assertThat(captor.getValue().item().get("SK").s()).isEqualTo("SAVED_ROUTE#" + route.savedItemId());
        assertThat(captor.getValue().item().get("coordinatesJson").s()).contains("-122.3321", "-80.13");
        assertThat(captor.getValue().expressionAttributeValues().get(":userId").s()).isEqualTo(route.userId().toString());
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
    void queriesOnlySavedRoutesAndMapsResults() {
        var savedItemId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var item = new HashMap<String, AttributeValue>();
        item.put("savedItemId", string(savedItemId.toString()));
        item.put("userId", string(userId.toString()));
        item.put("name", string("Seattle to Miami Beach"));
        item.put("originName", string("Seattle, WA"));
        item.put("destinationName", string("Miami Beach, FL"));
        item.put("vehicleType", string("CAR"));
        item.put("distanceMiles", number("3127"));
        item.put("durationMinutes", number("2910"));
        item.put("climateDelayMinutes", number("28"));
        item.put("riskScore", number("34"));
        item.put("coordinatesJson", string("[[-122.3321,47.6062],[-80.13,25.7907]]"));
        item.put("generatedAt", string("2026-06-21T12:00:00Z"));
        when(client.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder()
                .items(List.of(item))
                .build());

        assertThat(repository.findRoutes(userId))
                .containsExactly(new SavedRoute(savedItemId, userId, "Seattle to Miami Beach", "Seattle, WA",
                        "Miami Beach, FL", "CAR", 3127, 2910, 28, 34,
                        List.of(List.of(-122.3321, 47.6062), List.of(-80.13, 25.7907)),
                        "2026-06-21T12:00:00Z"));

        var captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client).query(captor.capture());
        assertThat(captor.getValue().expressionAttributeValues().get(":prefix").s()).isEqualTo("SAVED_ROUTE#");
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
        assertThat(captor.getValue().conditionExpression()).contains("attribute_not_exists(PK)", "userId = :userId");
        assertThat(captor.getValue().expressionAttributeValues().get(":userId").s()).isEqualTo(userId.toString());
    }

    @Test
    void deletesOnlyTheOwnedSavedRouteKey() {
        var userId = UUID.randomUUID();
        var savedItemId = UUID.randomUUID();

        repository.deleteRoute(userId, savedItemId);

        var captor = ArgumentCaptor.forClass(DeleteItemRequest.class);
        verify(client).deleteItem(captor.capture());
        assertThat(captor.getValue().key().get("PK").s()).isEqualTo("USER#" + userId);
        assertThat(captor.getValue().key().get("SK").s()).isEqualTo("SAVED_ROUTE#" + savedItemId);
        assertThat(captor.getValue().conditionExpression()).contains("attribute_not_exists(PK)", "userId = :userId");
        assertThat(captor.getValue().expressionAttributeValues().get(":userId").s()).isEqualTo(userId.toString());
    }

    @Test
    void readsSingleSavedRouteByOwnedCompositeKey() {
        var route = savedRoute();
        var item = new HashMap<String, AttributeValue>();
        item.put("savedItemId", string(route.savedItemId().toString()));
        item.put("userId", string(route.userId().toString()));
        item.put("name", string(route.name()));
        item.put("originName", string(route.originName()));
        item.put("destinationName", string(route.destinationName()));
        item.put("vehicleType", string(route.vehicleType()));
        item.put("distanceMiles", number(String.valueOf(route.distanceMiles())));
        item.put("durationMinutes", number(String.valueOf(route.durationMinutes())));
        item.put("climateDelayMinutes", number(String.valueOf(route.climateDelayMinutes())));
        item.put("riskScore", number(String.valueOf(route.riskScore())));
        item.put("coordinatesJson", string("[[-122.3321,47.6062],[-80.13,25.7907]]"));
        item.put("generatedAt", string(route.generatedAt()));
        when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(item).build());

        assertThat(repository.findRoute(route.userId(), route.savedItemId())).contains(route);

        var captor = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(client).getItem(captor.capture());
        assertThat(captor.getValue().consistentRead()).isTrue();
        assertThat(captor.getValue().key().get("PK").s()).isEqualTo("USER#" + route.userId());
        assertThat(captor.getValue().key().get("SK").s()).isEqualTo("SAVED_ROUTE#" + route.savedItemId());
    }

    private static SavedRoute savedRoute() {
        return new SavedRoute(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Seattle to Miami Beach",
                "Seattle, WA",
                "Miami Beach, FL",
                "CAR",
                3127,
                2910,
                28,
                34,
                List.of(List.of(-122.3321, 47.6062), List.of(-80.13, 25.7907)),
                "2026-06-21T12:00:00Z");
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(String value) {
        return AttributeValue.builder().n(value).build();
    }
}
