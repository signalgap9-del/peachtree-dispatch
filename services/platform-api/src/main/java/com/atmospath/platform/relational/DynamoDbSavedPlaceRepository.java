package com.atmospath.platform.relational;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final String SAVED_ROUTE_PREFIX = "SAVED_ROUTE#";
    private static final TypeReference<List<List<Double>>> COORDINATES_TYPE = new TypeReference<>() {
    };

    private final DynamoDbClient client;
    private final String tableName;
    private final ObjectMapper objectMapper;

    public DynamoDbSavedPlaceRepository(DynamoDbClient client, @Value("${atmospath.dynamodb-table}") String tableName) {
        this(client, tableName, new ObjectMapper());
    }

    DynamoDbSavedPlaceRepository(DynamoDbClient client, String tableName, ObjectMapper objectMapper) {
        this.client = client;
        this.tableName = tableName;
        this.objectMapper = objectMapper;
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
    public void saveRoute(SavedRoute route) {
        var now = Instant.now().toString();
        var item = new HashMap<String, AttributeValue>();
        item.put("PK", string(userKey(route.userId())));
        item.put("SK", string(SAVED_ROUTE_PREFIX + route.savedItemId()));
        item.put("entityType", string("SavedRoute"));
        item.put("savedItemId", string(route.savedItemId().toString()));
        item.put("userId", string(route.userId().toString()));
        item.put("name", string(route.name()));
        item.put("originName", string(route.originName()));
        item.put("destinationName", string(route.destinationName()));
        item.put("vehicleType", string(route.vehicleType()));
        item.put("distanceMiles", number(route.distanceMiles()));
        item.put("durationMinutes", number(route.durationMinutes()));
        item.put("climateDelayMinutes", number(route.climateDelayMinutes()));
        item.put("riskScore", number(route.riskScore()));
        item.put("coordinatesJson", string(writeCoordinates(route.coordinates())));
        item.put("generatedAt", string(route.generatedAt() == null ? "" : route.generatedAt()));
        item.put("usualDepartureTime", string(route.usualDepartureTime()));
        item.put("riskThreshold", number(route.riskThreshold()));
        item.put("monitorEnabled", AttributeValue.builder().bool(route.monitorEnabled()).build());
        item.put("lastCheckedAt", string(route.lastCheckedAt() == null ? now : route.lastCheckedAt()));
        item.put("activeHazardsJson", string(writeStringList(route.activeHazards())));
        item.put("riskTrend", string(route.riskTrend()));
        item.put("updatedAt", string(now));
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
    public List<SavedRoute> findRoutes(UUID userId) {
        var response = client.query(QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("PK = :pk AND begins_with(SK, :prefix)")
                .expressionAttributeValues(Map.of(
                        ":pk", string(userKey(userId)),
                        ":prefix", string(SAVED_ROUTE_PREFIX)))
                .limit(100)
                .build());
        return response.items().stream().map(this::toSavedRoute).toList();
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

    @Override
    public void deleteRoute(UUID userId, UUID savedItemId) {
        client.deleteItem(DeleteItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(
                        "PK", string(userKey(userId)),
                        "SK", string(SAVED_ROUTE_PREFIX + savedItemId)))
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

    private SavedRoute toSavedRoute(Map<String, AttributeValue> item) {
        return new SavedRoute(
                UUID.fromString(item.get("savedItemId").s()),
                UUID.fromString(item.get("userId").s()),
                item.get("name").s(),
                item.get("originName").s(),
                item.get("destinationName").s(),
                item.get("vehicleType").s(),
                Double.parseDouble(item.get("distanceMiles").n()),
                Double.parseDouble(item.get("durationMinutes").n()),
                Double.parseDouble(item.get("climateDelayMinutes").n()),
                Integer.parseInt(item.get("riskScore").n()),
                readCoordinates(item.get("coordinatesJson").s()),
                item.get("generatedAt").s(),
                stringValue(item, "usualDepartureTime", "08:00"),
                integerValue(item, "riskThreshold", 55),
                booleanValue(item, "monitorEnabled", true),
                stringValue(item, "lastCheckedAt", item.get("generatedAt").s()),
                readStringList(stringValue(item, "activeHazardsJson", "[]")),
                stringValue(item, "riskTrend", "STABLE"));
    }

    private String writeCoordinates(List<List<Double>> coordinates) {
        try {
            return objectMapper.writeValueAsString(coordinates);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Route coordinates could not be serialized.", exception);
        }
    }

    private List<List<Double>> readCoordinates(String value) {
        try {
            return objectMapper.readValue(value, COORDINATES_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Saved route coordinates could not be read.", exception);
        }
    }

    private String writeStringList(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Route hazards could not be serialized.", exception);
        }
    }

    private List<String> readStringList(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private static String stringValue(Map<String, AttributeValue> item, String key, String fallback) {
        var value = item.get(key);
        return value == null ? fallback : value.s();
    }

    private static int integerValue(Map<String, AttributeValue> item, String key, int fallback) {
        var value = item.get(key);
        return value == null ? fallback : Integer.parseInt(value.n());
    }

    private static boolean booleanValue(Map<String, AttributeValue> item, String key, boolean fallback) {
        var value = item.get(key);
        return value == null ? fallback : value.bool();
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
