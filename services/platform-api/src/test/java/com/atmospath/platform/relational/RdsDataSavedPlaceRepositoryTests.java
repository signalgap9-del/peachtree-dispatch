package com.atmospath.platform.relational;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import software.amazon.awssdk.services.rdsdata.RdsDataClient;
import software.amazon.awssdk.services.rdsdata.model.ExecuteStatementRequest;
import software.amazon.awssdk.services.rdsdata.model.ExecuteStatementResponse;
import software.amazon.awssdk.services.rdsdata.model.Field;

class RdsDataSavedPlaceRepositoryTests {
    private final RdsDataClient client = mock(RdsDataClient.class);
    private final RelationalStoreProperties properties =
            new RelationalStoreProperties(true, false, "atmospath", "cluster-arn", "secret-arn");
    private final RdsDataSavedPlaceRepository repository = new RdsDataSavedPlaceRepository(client, properties);

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

    private ExecuteStatementRequest captureRequest() {
        var captor = ArgumentCaptor.forClass(ExecuteStatementRequest.class);
        verify(client).executeStatement(captor.capture());
        return captor.getValue();
    }

    private static Field string(String value) {
        return Field.builder().stringValue(value).build();
    }

    private static Field decimal(double value) {
        return Field.builder().doubleValue(value).build();
    }
}
