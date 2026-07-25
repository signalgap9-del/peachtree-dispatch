package com.atmospath.platform.llm.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration tests for {@link VectorSearchRepository}. These require a
 * running PostgreSQL instance with the pgvector extension and are disabled
 * by default. Enable by providing a test datasource and removing @Disabled.
 */
@Disabled("Requires PostgreSQL with pgvector")
class VectorSearchRepositoryTests {

    // In a real integration test, this would be injected by Spring
    private VectorSearchRepository repository;

    @Test
    void vectorSearchReturnsResultsOrderedBySimilarity() {
        float[] queryEmbedding = new float[384];
        queryEmbedding[0] = 1.0f;

        List<SearchResult> results = repository.searchByVector(
                queryEmbedding, "route_risk_observation", 5);

        assertThat(results).isNotEmpty();
        assertThat(results).hasSizeLessThanOrEqualTo(5);

        // Results should be ordered by descending score
        for (int i = 1; i < results.size(); i++) {
            assertThat(results.get(i - 1).score())
                    .isGreaterThanOrEqualTo(results.get(i).score());
        }
    }

    @Test
    void filteredVectorSearchRespectsRouteId() {
        UUID routeId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        SearchFilters filters = new SearchFilters(
                routeId.toString(), null, null, null, null, null);

        float[] queryEmbedding = new float[384];
        queryEmbedding[0] = 1.0f;

        List<SearchResult> results = repository.searchWithFilters(
                queryEmbedding, filters, 10);

        assertThat(results).allSatisfy(r ->
                assertThat(r.routeId()).isEqualTo(routeId));
    }

    @Test
    void filteredVectorSearchRespectsTimeRange() {
        Instant from = Instant.now().minusSeconds(86400);
        Instant to = Instant.now();
        SearchFilters filters = new SearchFilters(null, from, to, null, null, null);

        float[] queryEmbedding = new float[384];
        queryEmbedding[0] = 1.0f;

        List<SearchResult> results = repository.searchWithFilters(
                queryEmbedding, filters, 10);

        assertThat(results).allSatisfy(r -> {
            assertThat(r.observationTime()).isAfterOrEqualTo(from);
            assertThat(r.observationTime()).isBeforeOrEqualTo(to);
        });
    }

    @Test
    void keywordSearchReturnsRelevantResults() {
        List<SearchResult> results = repository.searchByKeyword(
                "ice black ice highway", "route_risk_observation", 5);

        assertThat(results).isNotEmpty();
        assertThat(results).allSatisfy(r ->
                assertThat(r.factorsText()).isNotNull());
    }

    @Test
    void invalidTableNameIsRejected() {
        // This test does NOT need a database - it validates input sanitization
        JdbcTemplate mockJdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        VectorSearchRepository repo = new VectorSearchRepository(mockJdbc);

        assertThatThrownBy(() ->
                repo.searchByVector(new float[]{1.0f}, "DROP TABLE; --", 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid table name");
    }
}
