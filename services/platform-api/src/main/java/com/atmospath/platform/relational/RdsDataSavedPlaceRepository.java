package com.atmospath.platform.relational;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import software.amazon.awssdk.services.rdsdata.RdsDataClient;
import software.amazon.awssdk.services.rdsdata.model.Field;
import software.amazon.awssdk.services.rdsdata.model.SqlParameter;

@Repository
@ConditionalOnProperty(name = "atmospath.saved-place-store", havingValue = "postgis")
public class RdsDataSavedPlaceRepository implements SavedPlaceRepository {
    private static final TypeReference<List<List<Double>>> COORDINATES_TYPE = new TypeReference<>() {
    };
    private static final String ENSURE_USER_SQL = """
            INSERT INTO app_user(user_id, auth_subject, email)
            VALUES(CAST(:user_id AS uuid), :auth_subject, :email)
            ON CONFLICT(auth_subject) DO UPDATE SET email = EXCLUDED.email, updated_at = now()
            RETURNING user_id::text
            """;
    private static final String SAVE_SQL = """
            INSERT INTO saved_item(saved_item_id, user_id, item_type, name, point, current_risk_score)
            VALUES(CAST(:id AS uuid), CAST(:user_id AS uuid), 'PLACE', :name,
              ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography, NULLIF(:risk_score, -1))
            ON CONFLICT(saved_item_id) DO UPDATE SET
              name = EXCLUDED.name,
              point = EXCLUDED.point,
              current_risk_score = EXCLUDED.current_risk_score,
              updated_at = now()
            WHERE saved_item.user_id = EXCLUDED.user_id
            """;
    private static final String SAVE_ROUTE_SQL = """
            INSERT INTO saved_item(saved_item_id, user_id, item_type, name, path, metadata, current_risk_score)
            VALUES(CAST(:id AS uuid), CAST(:user_id AS uuid), 'ROUTE', :name,
              ST_GeogFromText(:path_wkt), CAST(:metadata AS jsonb), :risk_score)
            ON CONFLICT(saved_item_id) DO UPDATE SET
              name = EXCLUDED.name,
              path = EXCLUDED.path,
              metadata = EXCLUDED.metadata,
              current_risk_score = EXCLUDED.current_risk_score,
              updated_at = now()
            WHERE saved_item.user_id = EXCLUDED.user_id
            """;
    private static final String NEARBY_SQL = """
            SELECT saved_item_id::text, user_id::text, name,
              ST_X(point::geometry), ST_Y(point::geometry), current_risk_score
            FROM saved_item
            WHERE user_id = CAST(:user_id AS uuid)
              AND item_type = 'PLACE'
              AND deleted_at IS NULL
              AND ST_DWithin(
                point,
                ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
                :radius_meters)
            ORDER BY point <-> ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography
            LIMIT 50
            """;
    private static final String FIND_ALL_SQL = """
            SELECT saved_item_id::text, user_id::text, name,
              ST_X(point::geometry), ST_Y(point::geometry), current_risk_score
            FROM saved_item
            WHERE user_id = CAST(:user_id AS uuid)
              AND item_type = 'PLACE'
              AND deleted_at IS NULL
            ORDER BY updated_at DESC
            LIMIT 100
            """;
    private static final String FIND_ROUTES_SQL = """
            SELECT saved_item_id::text, user_id::text, name,
              ST_AsGeoJSON(path::geometry), metadata::text, current_risk_score
            FROM saved_item
            WHERE user_id = CAST(:user_id AS uuid)
              AND item_type = 'ROUTE'
              AND deleted_at IS NULL
            ORDER BY updated_at DESC
            LIMIT 100
            """;
    private static final String DELETE_SQL = """
            UPDATE saved_item SET deleted_at = now(), updated_at = now()
            WHERE saved_item_id = CAST(:id AS uuid)
              AND user_id = CAST(:user_id AS uuid)
              AND deleted_at IS NULL
            """;

    private final RdsDataClient client;
    private final RelationalStoreProperties properties;
    private final ObjectMapper objectMapper;

    public RdsDataSavedPlaceRepository(RdsDataClient client, RelationalStoreProperties properties) {
        this(client, properties, new ObjectMapper());
    }

    RdsDataSavedPlaceRepository(RdsDataClient client, RelationalStoreProperties properties, ObjectMapper objectMapper) {
        this.client = client;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public UUID ensureUser(String authSubject, String email) {
        var proposedId = UUID.nameUUIDFromBytes(authSubject.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var response = client.executeStatement(request(ENSURE_USER_SQL, List.of(
                string("user_id", proposedId.toString()),
                string("auth_subject", authSubject),
                string("email", email == null ? "" : email))));
        return UUID.fromString(response.records().getFirst().getFirst().stringValue());
    }

    @Override
    public void save(SavedPlace place) {
        client.executeStatement(request(SAVE_SQL, List.of(
                string("id", place.savedItemId().toString()),
                string("user_id", place.userId().toString()),
                string("name", place.name()),
                decimal("longitude", place.longitude()),
                decimal("latitude", place.latitude()),
                integer("risk_score", place.currentRiskScore()))));
    }

    @Override
    public void saveRoute(SavedRoute route) {
        client.executeStatement(request(SAVE_ROUTE_SQL, List.of(
                string("id", route.savedItemId().toString()),
                string("user_id", route.userId().toString()),
                string("name", route.name()),
                string("path_wkt", toLineString(route.coordinates())),
                string("metadata", writeMetadata(route)),
                integer("risk_score", route.riskScore()))));
    }

    @Override
    public List<SavedPlace> findNearby(UUID userId, double longitude, double latitude, double radiusMiles) {
        var response = client.executeStatement(request(NEARBY_SQL, List.of(
                string("user_id", userId.toString()),
                decimal("longitude", longitude),
                decimal("latitude", latitude),
                decimal("radius_meters", radiusMiles * 1609.344))));
        return mapPlaces(response.records());
    }

    @Override
    public List<SavedPlace> findAll(UUID userId) {
        return mapPlaces(client.executeStatement(request(FIND_ALL_SQL, List.of(
                string("user_id", userId.toString())))).records());
    }

    @Override
    public List<SavedRoute> findRoutes(UUID userId) {
        return mapRoutes(client.executeStatement(request(FIND_ROUTES_SQL, List.of(
                string("user_id", userId.toString())))).records());
    }

    @Override
    public void delete(UUID userId, UUID savedItemId) {
        client.executeStatement(request(DELETE_SQL, List.of(
                string("user_id", userId.toString()),
                string("id", savedItemId.toString()))));
    }

    @Override
    public void deleteRoute(UUID userId, UUID savedItemId) {
        delete(userId, savedItemId);
    }

    private static List<SavedPlace> mapPlaces(List<List<Field>> records) {
        return records.stream().map(row -> new SavedPlace(
                UUID.fromString(row.get(0).stringValue()),
                UUID.fromString(row.get(1).stringValue()),
                row.get(2).stringValue(),
                row.get(3).doubleValue(),
                row.get(4).doubleValue(),
                Boolean.TRUE.equals(row.get(5).isNull()) ? null : row.get(5).longValue().intValue())).toList();
    }

    private List<SavedRoute> mapRoutes(List<List<Field>> records) {
        return records.stream().map(row -> {
            var metadata = readMetadata(row.get(4).stringValue());
            return new SavedRoute(
                    UUID.fromString(row.get(0).stringValue()),
                    UUID.fromString(row.get(1).stringValue()),
                    row.get(2).stringValue(),
                    metadata.originName(),
                    metadata.destinationName(),
                    metadata.vehicleType(),
                    metadata.distanceMiles(),
                    metadata.durationMinutes(),
                    metadata.climateDelayMinutes(),
                    Boolean.TRUE.equals(row.get(5).isNull()) ? 0 : row.get(5).longValue().intValue(),
                    readCoordinates(row.get(3).stringValue()),
                    metadata.generatedAt());
        }).toList();
    }

    private String writeMetadata(SavedRoute route) {
        try {
            return objectMapper.writeValueAsString(new RouteMetadata(
                    route.originName(),
                    route.destinationName(),
                    route.vehicleType(),
                    route.distanceMiles(),
                    route.durationMinutes(),
                    route.climateDelayMinutes(),
                    route.generatedAt()));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Route metadata could not be serialized.", exception);
        }
    }

    private RouteMetadata readMetadata(String value) {
        try {
            return objectMapper.readValue(value, RouteMetadata.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Saved route metadata could not be read.", exception);
        }
    }

    private List<List<Double>> readCoordinates(String geoJson) {
        try {
            JsonNode coordinates = objectMapper.readTree(geoJson).get("coordinates");
            return objectMapper.convertValue(coordinates, COORDINATES_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Saved route geometry could not be read.", exception);
        }
    }

    private static String toLineString(List<List<Double>> coordinates) {
        return coordinates.stream()
                .map(point -> point.get(0) + " " + point.get(1))
                .collect(java.util.stream.Collectors.joining(", ", "LINESTRING(", ")"));
    }

    private software.amazon.awssdk.services.rdsdata.model.ExecuteStatementRequest request(
            String sql, List<SqlParameter> parameters) {
        return software.amazon.awssdk.services.rdsdata.model.ExecuteStatementRequest.builder()
                .database(properties.database())
                .resourceArn(properties.resourceArn())
                .secretArn(properties.secretArn())
                .sql(sql)
                .parameters(parameters)
                .build();
    }

    private static SqlParameter string(String name, String value) {
        return SqlParameter.builder().name(name).value(Field.builder().stringValue(value).build()).build();
    }

    private static SqlParameter decimal(String name, double value) {
        return SqlParameter.builder().name(name).value(Field.builder().doubleValue(value).build()).build();
    }

    private static SqlParameter integer(String name, Integer value) {
        return SqlParameter.builder()
                .name(name)
                .value(Field.builder().longValue(value == null ? -1L : value.longValue()).build())
                .build();
    }

    private record RouteMetadata(
            String originName,
            String destinationName,
            String vehicleType,
            double distanceMiles,
            double durationMinutes,
            double climateDelayMinutes,
            String generatedAt) {
    }
}
