package com.atmospath.platform.llm.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Unit tests for {@link VectorSearchRepository} using a mocked
 * {@link JdbcTemplate}. Verifies SQL construction, parameter binding,
 * filter appending, table-name sanitization, and row mapping.
 */
class VectorSearchRepositoryMockTests {

    private JdbcTemplate jdbcTemplate;
    private VectorSearchRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new VectorSearchRepository(jdbcTemplate);
    }

    @Test
    void searchByVectorBuildsCorrectSqlAndPassesLimit() {
        float[] embedding = {0.1f, 0.2f, 0.3f};
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(5)))
                .thenReturn(List.of());

        repository.searchByVector(embedding, "route_risk_observation", 5);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), eq(5));
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("FROM route_risk_observation");
        assertThat(sql).contains("'[0.1,0.2,0.3]'::vector");
        assertThat(sql).contains("ORDER BY embedding <=>");
        assertThat(sql).contains("LIMIT ?");
    }

    @Test
    void searchByVectorMapsRowsCorrectly() throws Exception {
        UUID obsId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        Instant time = Instant.parse("2025-06-15T10:30:00Z");

        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id", UUID.class)).thenReturn(obsId);
        when(rs.getInt("risk_score")).thenReturn(72);
        when(rs.getString("factors")).thenReturn("{\"wind\":0.5}");
        when(rs.getTimestamp("time")).thenReturn(Timestamp.from(time));
        when(rs.getObject("saved_route_id", UUID.class)).thenReturn(routeId);
        when(rs.getDouble("similarity")).thenReturn(0.95);

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(10)))
                .thenAnswer(inv -> {
                    RowMapper<SearchResult> mapper = inv.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });

        List<SearchResult> results = repository.searchByVector(
                new float[]{1.0f}, "route_risk_observation", 10);

        assertThat(results).hasSize(1);
        SearchResult r = results.get(0);
        assertThat(r.observationId()).isEqualTo(obsId);
        assertThat(r.riskScore()).isEqualTo(72);
        assertThat(r.factorsText()).contains("wind");
        assertThat(r.observationTime()).isEqualTo(time);
        assertThat(r.routeId()).isEqualTo(routeId);
        assertThat(r.score()).isEqualTo(0.95);
        assertThat(r.tableName()).isEqualTo("route_risk_observation");
    }

    @Test
    void searchWithFiltersAppendsAllFilterClauses() {
        UUID routeId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        Instant from = Instant.parse("2025-01-01T00:00:00Z");
        Instant to = Instant.parse("2025-12-31T23:59:59Z");
        SearchFilters filters = new SearchFilters(
                routeId.toString(), from, to, 50, "HIGH", tenantId.toString());

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        repository.searchWithFilters(new float[]{0.5f}, filters, 20);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), paramsCaptor.capture());

        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("saved_route_id = ?::uuid");
        assertThat(sql).contains("time >= ?");
        assertThat(sql).contains("time <= ?");
        assertThat(sql).contains("risk_score >= ?");
        assertThat(sql).contains("risk_level = ?");
        assertThat(sql).contains("tenant_id = ?::uuid");

        Object[] params = paramsCaptor.getValue();
        // 6 filter params + 1 limit param
        assertThat(params).hasSize(7);
        assertThat(params[params.length - 1]).isEqualTo(20);
    }

    @Test
    void searchWithNullFiltersSkipsFilterClauses() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        repository.searchWithFilters(new float[]{0.5f}, null, 10);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), paramsCaptor.capture());

        String sql = sqlCaptor.getValue();
        assertThat(sql).doesNotContain("saved_route_id =");
        assertThat(sql).doesNotContain("risk_score >=");
        // Only the limit param
        assertThat(paramsCaptor.getValue()).hasSize(1);
    }

    @Test
    void searchByKeywordPassesQueryTwiceAndLimit() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                eq("ice"), eq("ice"), eq(5)))
                .thenReturn(List.of());

        repository.searchByKeyword("ice", "route_risk_observation", 5);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class),
                eq("ice"), eq("ice"), eq(5));
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("plainto_tsquery");
        assertThat(sql).contains("ts_rank");
        assertThat(sql).contains("FROM route_risk_observation");
    }

    @Test
    void searchByKeywordWithFiltersCombinesParams() {
        UUID routeId = UUID.randomUUID();
        SearchFilters filters = new SearchFilters(
                routeId.toString(), null, null, null, null, null);

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        repository.searchByKeywordWithFilters("black ice", filters, 15);

        ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), paramsCaptor.capture());

        Object[] params = paramsCaptor.getValue();
        // query, query, routeId, limit
        assertThat(params).hasSize(4);
        assertThat(params[0]).isEqualTo("black ice");
        assertThat(params[1]).isEqualTo("black ice");
        assertThat(params[2]).isEqualTo(routeId.toString());
        assertThat(params[3]).isEqualTo(15);
    }

    @Test
    void invalidTableNameIsRejected() {
        assertThatThrownBy(() ->
                repository.searchByVector(new float[]{1.0f}, "DROP TABLE; --", 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid table name");
    }

    @Test
    void nullTableNameIsRejected() {
        assertThatThrownBy(() ->
                repository.searchByVector(new float[]{1.0f}, null, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void uppercaseTableNameIsRejected() {
        assertThatThrownBy(() ->
                repository.searchByVector(new float[]{1.0f}, "RouteTable", 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rowMapperHandlesNullTimestamp() throws Exception {
        UUID obsId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();

        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id", UUID.class)).thenReturn(obsId);
        when(rs.getInt("risk_score")).thenReturn(0);
        when(rs.getString("factors")).thenReturn(null);
        when(rs.getTimestamp("time")).thenReturn(null);
        when(rs.getObject("saved_route_id", UUID.class)).thenReturn(routeId);
        when(rs.getDouble("similarity")).thenReturn(0.0);

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(1)))
                .thenAnswer(inv -> {
                    RowMapper<SearchResult> mapper = inv.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });

        List<SearchResult> results = repository.searchByVector(
                new float[]{1.0f}, "route_risk_observation", 1);

        assertThat(results.get(0).observationTime()).isNull();
    }
}
