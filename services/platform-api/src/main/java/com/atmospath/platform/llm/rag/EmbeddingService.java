package com.atmospath.platform.llm.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.atmospath.platform.llm.LlmProperties;
import com.atmospath.platform.saas.entity.AlertEvent;
import com.atmospath.platform.saas.entity.RouteRiskObservationEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Generates and stores vector embeddings for risk observations and alert
 * events via an OpenAI-compatible {@code /v1/embeddings} endpoint (LiteLLM
 * proxy). Embedding failures are logged and skipped so the main request
 * flow is never blocked.
 */
@Service
@ConditionalOnProperty(name = "atmospath.llm.enabled", havingValue = "true")
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    private static final String EMBEDDING_MODEL = "text-embedding-v3";
    private static final int EMBEDDING_DIMENSIONS = 384;
    static final int MAX_BATCH_SIZE = 20;

    private final LlmProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    public EmbeddingService(LlmProperties properties, RestClient llmRestClient,
                            ObjectMapper objectMapper, JdbcTemplate jdbcTemplate) {
        this.properties = properties;
        this.restClient = llmRestClient;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---- Core embedding API ----

    /**
     * Embeds a single text and returns a 384-dim float vector,
     * or {@code null} if the API call fails.
     */
    public float[] embed(String text) {
        List<float[]> results = embedBatch(List.of(text));
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * Embeds a list of texts in batches of {@value MAX_BATCH_SIZE}.
     * Returns one vector per input text; failed batches produce no entries
     * for their texts.
     */
    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> allEmbeddings = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += MAX_BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + MAX_BATCH_SIZE, texts.size()));
            List<float[]> batchResult = callEmbeddingApi(batch);
            allEmbeddings.addAll(batchResult);
        }
        return allEmbeddings;
    }

    // ---- Domain-specific embedding ----

    /**
     * Builds a text representation of a route risk observation and embeds it.
     */
    public float[] embedObservation(RouteRiskObservationEntity obs) {
        String text = buildObservationText(obs);
        return embed(text);
    }

    /**
     * Builds a text representation of an alert event and embeds it.
     */
    public float[] embedAlert(AlertEvent alert) {
        String text = buildAlertText(alert);
        return embed(text);
    }

    // ---- Indexing operations ----

    /**
     * Embeds a single observation and stores the vector in the database.
     * Silently skips if embedding fails.
     */
    public void indexObservation(UUID savedRouteId, java.time.Instant time,
                                 RouteRiskObservationEntity obs) {
        float[] embedding = embedObservation(obs);
        if (embedding == null) {
            log.warn("Skipping embedding for observation route={} time={}", savedRouteId, time);
            return;
        }
        storeObservationEmbedding(savedRouteId, time, embedding);
        recordMetadata("route_risk_observation", savedRouteId + ":" + time);
    }

    /**
     * Embeds a single alert event and stores the vector in the database.
     * Silently skips if embedding fails.
     */
    public void indexAlert(UUID alertId, AlertEvent alert) {
        float[] embedding = embedAlert(alert);
        if (embedding == null) {
            log.warn("Skipping embedding for alert id={}", alertId);
            return;
        }
        storeAlertEmbedding(alertId, embedding);
        recordMetadata("alert_event", alertId.toString());
    }

    /**
     * Re-embeds all rows in the given table that have a null embedding.
     * Processes in batches and returns the total number of rows updated.
     */
    public int reindexAll(String tableName, int batchSize) {
        int totalUpdated = 0;
        if ("route_risk_observation".equals(tableName)) {
            totalUpdated = reindexObservations(batchSize);
        } else if ("alert_event".equals(tableName)) {
            totalUpdated = reindexAlerts(batchSize);
        } else {
            log.warn("Unknown table for reindex: {}", tableName);
        }
        log.info("Reindex complete for {}: {} rows embedded", tableName, totalUpdated);
        return totalUpdated;
    }

    // ---- Text builders (package-private for testing) ----

    String buildObservationText(RouteRiskObservationEntity obs) {
        String factors = obs.getFactors() != null ? obs.getFactors() : "{}";
        return String.format("Route %s, risk %d/100 (%s), factors: %s",
                obs.getSavedRouteId(),
                obs.getRiskScore() != null ? obs.getRiskScore() : 0,
                obs.getRiskLevel() != null ? obs.getRiskLevel() : "UNKNOWN",
                factors);
    }

    String buildAlertText(AlertEvent alert) {
        return String.format("%s alert: risk score %d, state %s, triggered at %s",
                alert.getSeverity() != null ? alert.getSeverity().name() : "UNKNOWN",
                alert.getRiskScore() != null ? alert.getRiskScore() : 0,
                alert.getState() != null ? alert.getState().name() : "UNKNOWN",
                alert.getTriggeredAt());
    }

    // ---- HTTP call ----

    List<float[]> callEmbeddingApi(List<String> texts) {
        try {
            Map<String, Object> body = Map.of(
                    "model", EMBEDDING_MODEL,
                    "input", texts);

            return restClient.post()
                    .uri("/v1/embeddings")
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .body(body)
                    .exchange((req, resp) -> {
                        if (resp.getStatusCode().isError()) {
                            log.error("Embedding API returned HTTP {}", resp.getStatusCode().value());
                            return List.<float[]>of();
                        }
                        JsonNode root = objectMapper.readTree(resp.getBody());
                        JsonNode data = root.path("data");
                        List<float[]> embeddings = new ArrayList<>();
                        for (JsonNode item : data) {
                            JsonNode arr = item.path("embedding");
                            float[] vec = new float[arr.size()];
                            for (int i = 0; i < arr.size(); i++) {
                                vec[i] = (float) arr.get(i).asDouble();
                            }
                            embeddings.add(vec);
                        }
                        return embeddings;
                    });
        } catch (Exception ex) {
            log.error("Embedding API call failed: {}", ex.getMessage(), ex);
            return List.of();
        }
    }

    // ---- Database operations ----

    private void storeObservationEmbedding(UUID savedRouteId, java.time.Instant time,
                                           float[] embedding) {
        jdbcTemplate.update(
                "UPDATE route_risk_observation SET embedding = ?::vector WHERE saved_route_id = ? AND time = ?",
                toVectorString(embedding), savedRouteId, java.sql.Timestamp.from(time));
    }

    private void storeAlertEmbedding(UUID alertId, float[] embedding) {
        jdbcTemplate.update(
                "UPDATE alert_event SET embedding = ?::vector WHERE id = ?",
                toVectorString(embedding), alertId);
    }

    private void recordMetadata(String tableName, String recordId) {
        jdbcTemplate.update("""
                INSERT INTO embedding_metadata (table_name, record_id, embedding_model)
                VALUES (?, ?, ?)
                ON CONFLICT (table_name, record_id, embedding_model) DO UPDATE
                SET embedding_version = embedding_metadata.embedding_version + 1,
                    created_at = now()
                """, tableName, recordId, EMBEDDING_MODEL);
    }

    private int reindexObservations(int batchSize) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT saved_route_id, time, risk_score, risk_level, factors
                FROM route_risk_observation
                WHERE embedding IS NULL
                LIMIT ?
                """, batchSize);
        int count = 0;
        for (Map<String, Object> row : rows) {
            UUID routeId = (UUID) row.get("saved_route_id");
            java.time.Instant time = ((java.sql.Timestamp) row.get("time")).toInstant();
            var obs = new RouteRiskObservationEntity(
                    time, routeId,
                    row.get("risk_score") != null ? ((Number) row.get("risk_score")).intValue() : null,
                    (String) row.get("risk_level"),
                    row.get("factors") != null ? row.get("factors").toString() : "{}");
            float[] embedding = embedObservation(obs);
            if (embedding != null) {
                storeObservationEmbedding(routeId, time, embedding);
                recordMetadata("route_risk_observation", routeId + ":" + time);
                count++;
            }
        }
        return count;
    }

    private int reindexAlerts(int batchSize) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, severity, risk_score, state, triggered_at
                FROM alert_event
                WHERE embedding IS NULL
                LIMIT ?
                """, batchSize);
        int count = 0;
        for (Map<String, Object> row : rows) {
            UUID id = (UUID) row.get("id");
            var alert = new AlertEvent(
                    null, null,
                    com.atmospath.platform.saas.entity.AlertSeverity.valueOf((String) row.get("severity")),
                    row.get("risk_score") != null ? ((Number) row.get("risk_score")).intValue() : 0);
            float[] embedding = embedAlert(alert);
            if (embedding != null) {
                storeAlertEmbedding(id, embedding);
                recordMetadata("alert_event", id.toString());
                count++;
            }
        }
        return count;
    }

    /** Converts a float array to pgvector literal format: [0.1,0.2,...] */
    static String toVectorString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    public static int embeddingDimensions() {
        return EMBEDDING_DIMENSIONS;
    }
}
