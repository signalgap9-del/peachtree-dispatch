package com.atmospath.platform.relational;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import software.amazon.awssdk.services.rdsdata.RdsDataClient;
import software.amazon.awssdk.services.rdsdata.model.BeginTransactionRequest;
import software.amazon.awssdk.services.rdsdata.model.BeginTransactionResponse;
import software.amazon.awssdk.services.rdsdata.model.ExecuteStatementRequest;
import software.amazon.awssdk.services.rdsdata.model.ExecuteStatementResponse;
import software.amazon.awssdk.services.rdsdata.model.Field;

class RdsDataSavedPlaceRepositoryTests {
    private final RdsDataClient client = mock(RdsDataClient.class);
    private final RelationalStoreProperties properties =
            new RelationalStoreProperties(true, false, "atmospath", "cluster-arn", "secret-arn");
    private final RdsDataSavedPlaceRepository repository = new RdsDataSavedPlaceRepository(client, properties);

    @BeforeEach
    void setUpTransaction() {
        when(client.beginTransaction(any(BeginTransactionRequest.class))).thenReturn(
                BeginTransactionResponse.builder().transactionId("tx-1").build());
    }

    @Test
    void writesAPlaceUsingPostgisPointConstruction() {
        when(client.executeStatement(any(ExecuteStatementRequest.class)))
                .thenReturn(ExecuteStatementResponse.builder().build());
        var place = new SavedPlace(UUID.randomUUID(), UUID.randomUUID(), "Atlanta", -84.388, 33.749, 42);

        repository.save(place);

        var request = captureRequest();
        assertThat(request.sql()).contains("ST_MakePoint", "ON CONFLICT", "saved_item.user_id = EXCLUDED.user_id");
        assertThat(request.parameters()).hasSize(6);
        assertThat(request.resourceArn()).isEqualTo("cluster-arn");
        assertThat(request.transactionId()).isEqualTo("tx-1");
    }

    @Test
    void writesARouteUsingPostgisLineStringAndMetadata() {
        when(client.executeStatement(any(ExecuteStatementRequest.class)))
                .thenReturn(ExecuteStatementResponse.builder().build());
        var route = new SavedRoute(
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

        repository.saveRoute(route);

        var request = captureRequest();
        assertThat(request.sql()).contains("'ROUTE'", "ST_GeogFromText", "CAST(:metadata AS jsonb)");
        assertThat(request.parameters()).hasSize(6);
        assertThat(request.parameters().stream().filter(parameter -> parameter.name().equals("path_wkt")).findFirst()
                .orElseThrow().value().stringValue()).isEqualTo("LINESTRING(-122.3321 47.6062, -80.13 25.7907)");
    }

    @Test
    void projectsAuthenticatedUserByImmutableSubject() {
        var userId = UUID.randomUUID();
        when(client.executeStatement(any(ExecuteStatementRequest.class))).thenReturn(
                ExecuteStatementResponse.builder().records(List.of(List.of(string(userId.toString())))).build());

        assertThat(repository.ensureUser("cognito-subject", "person@example.com")).isEqualTo(userId);
        assertThat(captureRequest().sql()).contains("ON CONFLICT(auth_subject)", "RETURNING user_id");
    }

    @Test
    void mapsNearbyPostgisQueryResults() {
        var savedItemId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        when(client.executeStatement(any(ExecuteStatementRequest.class))).thenReturn(
                ExecuteStatementResponse.builder()
                        .records(List.of(List.of(
                                string(savedItemId.toString()),
                                string(userId.toString()),
                                string("Atlanta"),
                                decimal(-84.388),
                                decimal(33.749),
                                Field.builder().longValue(42L).build())))
                        .build());

        var results = repository.findNearby(userId, -84.4, 33.75, 25);

        assertThat(results).containsExactly(new SavedPlace(savedItemId, userId, "Atlanta", -84.388, 33.749, 42));
        assertThat(captureRequest().sql()).contains("ST_DWithin", "LIMIT 50");
    }

    @Test
    void mapsSavedRoutesFromPostgisResults() {
        var savedItemId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        when(client.executeStatement(any(ExecuteStatementRequest.class))).thenReturn(
                ExecuteStatementResponse.builder()
                        .records(List.of(List.of(
                                string(savedItemId.toString()),
                                string(userId.toString()),
                                string("Seattle to Miami Beach"),
                                string("{\"type\":\"LineString\",\"coordinates\":[[-122.3321,47.6062],[-80.13,25.7907]]}"),
                                string("{\"originName\":\"Seattle, WA\",\"destinationName\":\"Miami Beach, FL\",\"vehicleType\":\"CAR\",\"distanceMiles\":3127,\"durationMinutes\":2910,\"climateDelayMinutes\":28,\"generatedAt\":\"2026-06-21T12:00:00Z\"}"),
                                Field.builder().longValue(34L).build())))
                        .build());

        var results = repository.findRoutes(userId);

        assertThat(results).containsExactly(new SavedRoute(savedItemId, userId, "Seattle to Miami Beach",
                "Seattle, WA", "Miami Beach, FL", "CAR", 3127, 2910, 28, 34,
                List.of(List.of(-122.3321, 47.6062), List.of(-80.13, 25.7907)), "2026-06-21T12:00:00Z"));
        assertThat(captureRequest().sql()).contains("item_type = 'ROUTE'", "ST_AsGeoJSON");
    }

    @Test
    void writesRouteRiskObservationIntoRiskExposure() {
        when(client.executeStatement(any(ExecuteStatementRequest.class)))
                .thenReturn(ExecuteStatementResponse.builder().build());
        var observation = routeRiskObservation();

        repository.recordRouteRiskObservation(observation);

        var request = captureRequest();
        assertThat(request.sql()).contains("INSERT INTO risk_exposure", "ROUTE_SUMMARY", "CAST(:metadata AS jsonb)");
        assertThat(request.parameters()).hasSize(7);
        assertThat(request.parameters().stream().filter(parameter -> parameter.name().equals("metadata")).findFirst()
                .orElseThrow().value().stringValue()).contains("Flash Flood Warning");
    }

    @Test
    void readsRouteRiskHistoryFromRiskExposure() {
        var observation = routeRiskObservation();
        when(client.executeStatement(any(ExecuteStatementRequest.class))).thenReturn(
                ExecuteStatementResponse.builder()
                        .records(List.of(List.of(
                                string(observation.observationId().toString()),
                                string(observation.savedItemId().toString()),
                                string(observation.observedAt()),
                                Field.builder().longValue(82L).build(),
                                string("{\"riskTrend\":\"RISING\",\"activeHazards\":[\"Flash Flood Warning\"]}"),
                                string("SAVED_ROUTE_CREATED"),
                                string("route-risk-v1"))))
                        .build());

        assertThat(repository.findRouteRiskHistory(observation.userId(), observation.savedItemId(), 30))
                .containsExactly(observation);

        var request = captureRequest();
        assertThat(request.sql()).contains("FROM risk_exposure", "ORDER BY observed_at DESC");
    }

    private ExecuteStatementRequest captureRequest() {
        var captor = ArgumentCaptor.forClass(ExecuteStatementRequest.class);
        verify(client, times(2)).executeStatement(captor.capture());
        assertThat(captor.getAllValues().getFirst().sql()).contains("set_config('app.user_id'");
        return captor.getAllValues().getLast();
    }

    private static Field string(String value) {
        return Field.builder().stringValue(value).build();
    }

    private static Field decimal(double value) {
        return Field.builder().doubleValue(value).build();
    }

    private static RouteRiskObservation routeRiskObservation() {
        return new RouteRiskObservation(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "2026-07-07T08:00:00Z",
                82,
                "RISING",
                List.of("Flash Flood Warning"),
                "SAVED_ROUTE_CREATED",
                "route-risk-v1");
    }
}
