package com.atmospath.platform.relational;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

@Repository
@ConditionalOnProperty(name = "atmospath.saved-place-store", havingValue = "dynamodb")
public class DynamoDbSavedPlaceRepository implements SavedPlaceRepository {
    private static final String SAVED_PLACE_PREFIX = "SAVED_PLACE#";

    private final DynamoDbClient client;
    private final String tableName;

    public DynamoDbSavedPlaceRepository(DynamoDbClient client, @Value("${atmospath.dynamodb-table}") String tableName) {
        this.client = client;
        this.tableName = tableName;
    }

    @Override
    public UUID ensureUser(String authSubject, String email) {
        var userId = UUID.nameUUIDFromBytes(authSubject.getBytes(StandardCharsets.UTF_8));
        var now = Instant.now().toString();
        client.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(Map.of(
                        "PK", string(userKey(userId)),
                        "SK", string("PROFILE"),
                        "entityType", string("User"),
                        "authSubject", string(authSubject),
                        "email", string(email == null ? "" : email),
                        "updatedAt", string(now)))
                .build());
        return userId;
    }

    @Override
    public void save(SavedPlace place) {
        var now = Instant.now().toString();
        var item = new HashMap<String, AttributeValue>();
        item.put("PK", string(userKey(place.userId())));
        item.put("SK", string(SAVED_PLACE_PREFIX + place.savedItemId()));
        item.put("entityType", string("SavedPlace"));
        item.put("savedItemId", string(place.savedItemId().toString()));
        item.put("userId", string(place.userId().toString()));
        item.put("name", string(place.name()));
        item.put("longitude", number(place.longitude()));
        item.put("latitude", number(place.latitude()));
        item.put("updatedAt", string(now));
        if (place.currentRiskScore() != null) {
            item.put("currentRiskScore", number(place.currentRiskScore()));
        }
        client.putItem(PutItemRequest.builder().tableName(tableName).item(item).build());
    }

    @Override
    public List<SavedPlace> findAll(UUID userId) {
        var response = client.query(QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("PK = :pk AND begins_with(SK, :prefix)")
                .expressionAttributeValues(Map.of(
                        ":pk", string(userKey(userId)),
                        ":prefix", string(SAVED_PLACE_PREFIX)))
                .limit(100)
                .build());
        return response.items().stream().map(DynamoDbSavedPlaceRepository::toSavedPlace).toList();
    }

    @Override
    public List<SavedPlace> findNearby(UUID userId, double longitude, double latitude, double radiusMiles) {
        return findAll(userId).stream()
                .filter(place -> distanceMiles(latitude, longitude, place.latitude(), place.longitude()) <= radiusMiles)
                .limit(50)
                .toList();
    }

    @Override
    public void delete(UUID userId, UUID savedItemId) {
        client.deleteItem(DeleteItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(
                        "PK", string(userKey(userId)),
                        "SK", string(SAVED_PLACE_PREFIX + savedItemId)))
                .build());
    }

    private static SavedPlace toSavedPlace(Map<String, AttributeValue> item) {
        var risk = item.get("currentRiskScore");
        return new SavedPlace(
                UUID.fromString(item.get("savedItemId").s()),
                UUID.fromString(item.get("userId").s()),
                item.get("name").s(),
                Double.parseDouble(item.get("longitude").n()),
                Double.parseDouble(item.get("latitude").n()),
                risk == null ? null : Integer.valueOf(risk.n()));
    }

    private static double distanceMiles(double latitudeA, double longitudeA, double latitudeB, double longitudeB) {
        var latDistance = Math.toRadians(latitudeB - latitudeA);
        var lonDistance = Math.toRadians(longitudeB - longitudeA);
        var a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(latitudeA)) * Math.cos(Math.toRadians(latitudeB))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        return 3958.8 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static String userKey(UUID userId) {
        return "USER#" + userId;
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(Number value) {
        return AttributeValue.builder().n(value.toString()).build();
    }
}
