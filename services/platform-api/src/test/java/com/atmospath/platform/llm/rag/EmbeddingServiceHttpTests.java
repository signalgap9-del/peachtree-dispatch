package com.atmospath.platform.llm.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.atmospath.platform.llm.LlmProperties;
import com.atmospath.platform.saas.entity.AlertEvent;
import com.atmospath.platform.saas.entity.AlertSeverity;
import com.atmospath.platform.saas.entity.RouteRiskObservationEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Tests for {@link EmbeddingService} that exercise the real HTTP exchange
 * path via {@link MockRestServiceServer}, covering response parsing,
 * error handling, and database indexing operations.
 */
class EmbeddingServiceHttpTests {

    private static final String API_KEY = "embed-test-key";

    private EmbeddingService service;
    private MockRestServiceServer mockServer;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        var props = new LlmProperties(true, "http://localhost:4000", API_KEY,
                "gpt-4o-mini", 1024, 0.7, 60_000L, 100_000L, 4096);
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new EmbeddingService(props, restClient, new ObjectMapper(), jdbcTemplate);
    }

    private String embeddingResponse(int dims, int count) {
        StringBuilder sb = new StringBuilder("{\"data\":[");
        for (int c = 0; c < count; c++) {
            if (c > 0) sb.append(',');
            sb.append("{\"embedding\":[");
            for (int i = 0; i < dims; i++) {
                if (i > 0) sb.append(',');
                sb.append(0.1 * (i + 1));
            }
            sb.append("]}");
        }
        sb.append("]}");
        return sb.toString();
    }

    @Test
    void callEmbeddingApiParsesVectorsCorrectly() {
        mockServer.expect(requestTo("/v1/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andRespond(withSuccess(embeddingResponse(3, 2), MediaType.APPLICATION_JSON));

        List<float[]> results = service.callEmbeddingApi(List.of("text1", "text2"));

        assertThat(results).hasSize(2);
        assertThat(results.get(0)).hasSize(3);
        assertThat(results.get(0)[0]).isCloseTo(0.1f, org.assertj.core.data.Offset.offset(0.001f));
        assertThat(results.get(1)[2]).isCloseTo(0.3f, org.assertj.core.data.Offset.offset(0.001f));
        mockServer.verify();
    }

    @Test
    void callEmbeddingApiReturnsEmptyListOnServerError() {
        mockServer.expect(requestTo("/v1/embeddings"))
                .andRespond(withServerError());

        List<float[]> results = service.callEmbeddingApi(List.of("text"));

        assertThat(results).isEmpty();
    }

    @Test
    void callEmbeddingApiReturnsEmptyListOn429() {
        mockServer.expect(requestTo("/v1/embeddings"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        List<float[]> results = service.callEmbeddingApi(List.of("text"));

        assertThat(results).isEmpty();
    }

    @Test
    void callEmbeddingApiReturnsEmptyListOnConnectionFailure() {
        // Build a service pointing at an unreachable host
        var props = new LlmProperties(true, "http://192.0.2.1:1", API_KEY,
                "gpt-4o-mini", 1024, 0.7, 1000L, 100_000L, 4096);
        RestClient failClient = RestClient.builder()
                .baseUrl("http://192.0.2.1:1")
                .build();
        EmbeddingService failService = new EmbeddingService(
                props, failClient, new ObjectMapper(), jdbcTemplate);

        List<float[]> results = failService.callEmbeddingApi(List.of("text"));

        assertThat(results).isEmpty();
    }

    @Test
    void embedUsesHttpAndReturnsVector() {
        mockServer.expect(requestTo("/v1/embeddings"))
                .andRespond(withSuccess(embeddingResponse(384, 1), MediaType.APPLICATION_JSON));

        float[] result = service.embed("test text");

        assertThat(result).isNotNull().hasSize(384);
        mockServer.verify();
    }

    @Test
    void indexObservationStoresEmbeddingAndMetadata() {
        mockServer.expect(requestTo("/v1/embeddings"))
                .andRespond(withSuccess(embeddingResponse(384, 1), MediaType.APPLICATION_JSON));
        when(jdbcTemplate.update(anyString(), (Object[]) org.mockito.ArgumentMatchers.any()))
                .thenReturn(1);

        UUID routeId = UUID.randomUUID();
        Instant time = Instant.now();
        var obs = new RouteRiskObservationEntity(time, routeId, 65, "MODERATE", "{}");

        service.indexObservation(routeId, time, obs);

        // Verify DB interactions happened (embedding store + metadata)
        var details = org.mockito.Mockito.mockingDetails(jdbcTemplate);
        long updateCalls = details.getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("update"))
                .count();
        assertThat(updateCalls).isEqualTo(2);
    }

    @Test
    void indexAlertStoresEmbeddingAndMetadata() {
        mockServer.expect(requestTo("/v1/embeddings"))
                .andRespond(withSuccess(embeddingResponse(384, 1), MediaType.APPLICATION_JSON));
        when(jdbcTemplate.update(anyString(), (Object[]) org.mockito.ArgumentMatchers.any()))
                .thenReturn(1);

        UUID alertId = UUID.randomUUID();
        var alert = new AlertEvent(UUID.randomUUID(), UUID.randomUUID(),
                AlertSeverity.HIGH, 88);

        service.indexAlert(alertId, alert);

        // Verify DB interactions happened (embedding store + metadata)
        var details = org.mockito.Mockito.mockingDetails(jdbcTemplate);
        long updateCalls = details.getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("update"))
                .count();
        assertThat(updateCalls).isEqualTo(2);
    }

    @Test
    void reindexAlertsProcessesRows() {
        mockServer.expect(requestTo("/v1/embeddings"))
                .andRespond(withSuccess(embeddingResponse(384, 1), MediaType.APPLICATION_JSON));

        UUID alertId = UUID.randomUUID();
        var rows = List.<java.util.Map<String, Object>>of(
                java.util.Map.of("id", alertId, "severity", "HIGH",
                        "risk_score", 75, "state", "TRIGGERED",
                        "triggered_at", java.sql.Timestamp.from(Instant.now())));
        when(jdbcTemplate.queryForList(anyString(), (Object[]) org.mockito.ArgumentMatchers.any()))
                .thenReturn(rows);
        when(jdbcTemplate.update(anyString(), (Object[]) org.mockito.ArgumentMatchers.any()))
                .thenReturn(1);

        int count = service.reindexAll("alert_event", 10);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void reindexUnknownTableReturnsZero() {
        int count = service.reindexAll("nonexistent_table", 10);
        assertThat(count).isZero();
    }

    @Test
    void embeddingDimensionsReturns384() {
        assertThat(EmbeddingService.embeddingDimensions()).isEqualTo(384);
    }
}
