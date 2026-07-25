package com.atmospath.platform.llm.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.atmospath.platform.llm.LlmProperties;
import com.atmospath.platform.saas.entity.AlertEvent;
import com.atmospath.platform.saas.entity.AlertSeverity;
import com.atmospath.platform.saas.entity.RouteRiskObservationEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

class EmbeddingServiceTests {

    private EmbeddingService service;
    private JdbcTemplate jdbcTemplate;
    private RestClient restClient;

    @BeforeEach
    void setUp() {
        var props = new LlmProperties(true, "http://localhost:4000", "test-key",
                "gpt-4o-mini", 1024, 0.7, 60_000L, 100_000L, 4096);
        restClient = mock(RestClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new EmbeddingService(props, restClient, new ObjectMapper(), jdbcTemplate);
    }

    @Test
    void embedReturnsCorrectDimensions() {
        // Stub callEmbeddingApi via a spy to isolate HTTP
        var spy = org.mockito.Mockito.spy(service);
        float[] fakeVector = new float[384];
        org.mockito.Mockito.doReturn(List.of(fakeVector))
                .when(spy).callEmbeddingApi(any());

        float[] result = spy.embed("test text");

        assertThat(result).isNotNull();
        assertThat(result).hasSize(384);
    }

    @Test
    void embedBatchSplitsIntoBatchesOf20() {
        var spy = org.mockito.Mockito.spy(service);
        // 45 texts should produce 3 API calls (20 + 20 + 5)
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            texts.add("text " + i);
        }

        org.mockito.Mockito.doAnswer(inv -> {
            List<String> batch = inv.getArgument(0);
            List<float[]> result = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) {
                result.add(new float[384]);
            }
            return result;
        }).when(spy).callEmbeddingApi(any());

        List<float[]> results = spy.embedBatch(texts);

        assertThat(results).hasSize(45);
        verify(spy, times(3)).callEmbeddingApi(any());
    }

    @Test
    void embedObservationBuildsCorrectText() {
        var obs = new RouteRiskObservationEntity(
                Instant.now(), UUID.randomUUID(), 72, "HIGH",
                "{\"precipitation\": 0.8, \"wind\": 0.3}");

        String text = service.buildObservationText(obs);

        assertThat(text).contains("risk 72/100");
        assertThat(text).contains("(HIGH)");
        assertThat(text).contains("precipitation");
    }

    @Test
    void embedAlertBuildsCorrectText() {
        var alert = new AlertEvent(UUID.randomUUID(), UUID.randomUUID(),
                AlertSeverity.SEVERE, 91);

        String text = service.buildAlertText(alert);

        assertThat(text).contains("SEVERE alert");
        assertThat(text).contains("risk score 91");
        assertThat(text).contains("TRIGGERED");
    }

    @Test
    void apiFailureReturnsNullAndDoesNotThrow() {
        var spy = org.mockito.Mockito.spy(service);
        org.mockito.Mockito.doReturn(List.of())
                .when(spy).callEmbeddingApi(any());

        float[] result = spy.embed("some text");

        assertThat(result).isNull();
    }

    @Test
    void indexObservationSkipsOnEmbeddingFailure() {
        var spy = org.mockito.Mockito.spy(service);
        org.mockito.Mockito.doReturn(List.of())
                .when(spy).callEmbeddingApi(any());

        var obs = new RouteRiskObservationEntity(
                Instant.now(), UUID.randomUUID(), 50, "MODERATE", "{}");

        spy.indexObservation(obs.getSavedRouteId(), obs.getTime(), obs);

        verify(jdbcTemplate, never()).update(anyString(), (Object[]) any());
    }

    @Test
    void reindexAllProcessesInBatchesAndReportsCount() {
        var spy = org.mockito.Mockito.spy(service);
        float[] fakeVector = new float[384];
        org.mockito.Mockito.doReturn(List.of(fakeVector))
                .when(spy).callEmbeddingApi(any());

        // Simulate 3 rows returned from DB
        var rows = List.<java.util.Map<String, Object>>of(
                java.util.Map.of("saved_route_id", UUID.randomUUID(),
                        "time", java.sql.Timestamp.from(Instant.now()),
                        "risk_score", 60, "risk_level", "MODERATE", "factors", "{}"),
                java.util.Map.of("saved_route_id", UUID.randomUUID(),
                        "time", java.sql.Timestamp.from(Instant.now()),
                        "risk_score", 80, "risk_level", "HIGH", "factors", "{}"),
                java.util.Map.of("saved_route_id", UUID.randomUUID(),
                        "time", java.sql.Timestamp.from(Instant.now()),
                        "risk_score", 30, "risk_level", "LOW", "factors", "{}"));
        when(jdbcTemplate.queryForList(anyString(), (Object[]) any())).thenReturn(rows);
        when(jdbcTemplate.update(anyString(), (Object[]) any())).thenReturn(1);

        int count = spy.reindexAll("route_risk_observation", 10);

        assertThat(count).isEqualTo(3);
    }

    @Test
    void toVectorStringFormatsCorrectly() {
        float[] vec = {0.1f, 0.2f, 0.3f};
        String result = EmbeddingService.toVectorString(vec);
        assertThat(result).startsWith("[").endsWith("]");
        assertThat(result.split(",")).hasSize(3);
    }
}
