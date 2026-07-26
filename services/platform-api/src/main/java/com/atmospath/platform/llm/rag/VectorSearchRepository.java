package com.atmospath.platform.llm.rag;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Native SQL queries against pgvector-enabled observation tables.
 * Supports pure vector similarity, filtered vector search (HNSW + btree),
 * and full-text keyword search for RRF combination.
 */
@Repository
@ConditionalOnProperty(name = "atmospath.llm.enabled", havingValue = "true")
public class VectorSearchRepository {

    private static final Logger log = LoggerFactory.getLogger(VectorSearchRepository.class);
    private static final String DEFAULT_TABLE = "route_risk_observation";

    private final JdbcTemplate jdbcTemplate;

    public VectorSearchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Pure vector similarity search using cosine distance.
     */
    public List<SearchResult> searchByVector(float[] queryEmbedding, String tableName, int limit) {
        String table = sanitizeTableName(tableName);
        String vectorLiteral = toVectorLiteral(queryEmbedding);

        String sql = """
                SELECT id, risk_score, factors, time, saved_route_id,
                       1 - (embedding <=> '%s'::vector) AS similarity
                FROM %s
                WHERE embedding IS NOT NULL
                ORDER BY embedding <=> '%s'::vector
                LIMIT ?
                """.formatted(vectorLiteral, table, vectorLiteral);

        log.debug("Vector search on {} with limit {}", table, limit);
        return jdbcTemplate.query(sql, new SearchResultRowMapper(table), limit);
    }

    /**
     * Vector search with optional SQL filters. Combines HNSW index for
     * similarity with btree indexes for filter predicates.
     */
    public List<SearchResult> searchWithFilters(float[] queryEmbedding,
                                                SearchFilters filters,
                                                int limit) {
        String vectorLiteral = toVectorLiteral(queryEmbedding);
        StringBuilder sql = new StringBuilder();
        sql.append("""
                SELECT id, risk_score, factors, time, saved_route_id,
                       1 - (embedding <=> '%s'::vector) AS similarity
                FROM %s
                WHERE embedding IS NOT NULL
                """.formatted(vectorLiteral, DEFAULT_TABLE));

        List<Object> params = new ArrayList<>();
        appendFilters(sql, filters, params);

        sql.append(" ORDER BY embedding <=> '").append(vectorLiteral).append("'::vector");
        sql.append(" LIMIT ?");
        params.add(limit);

        log.debug("Filtered vector search with {} params, limit {}", params.size() - 1, limit);
        return jdbcTemplate.query(sql.toString(), new SearchResultRowMapper(DEFAULT_TABLE),
                params.toArray());
    }

    /**
     * Full-text keyword search using PostgreSQL tsvector/tsquery.
     * Used as the second ranking signal for RRF fusion.
     */
    public List<SearchResult> searchByKeyword(String query, String tableName, int limit) {
        String table = sanitizeTableName(tableName);

        String sql = """
                SELECT id, risk_score, factors, time, saved_route_id,
                       ts_rank(to_tsvector('english', factors::text), plainto_tsquery('english', ?)) AS similarity
                FROM %s
                WHERE to_tsvector('english', factors::text) @@ plainto_tsquery('english', ?)
                ORDER BY similarity DESC
                LIMIT ?
                """.formatted(table);

        log.debug("Keyword search on {} for '{}', limit {}", table, query, limit);
        return jdbcTemplate.query(sql, new SearchResultRowMapper(table), query, query, limit);
    }

    /**
     * Keyword search with the same filter set as vector search.
     */
    public List<SearchResult> searchByKeywordWithFilters(String query,
                                                         SearchFilters filters,
                                                         int limit) {
        StringBuilder sql = new StringBuilder();
        sql.append("""
                SELECT id, risk_score, factors, time, saved_route_id,
                       ts_rank(to_tsvector('english', factors::text), plainto_tsquery('english', ?)) AS similarity
                FROM %s
                WHERE to_tsvector('english', factors::text) @@ plainto_tsquery('english', ?)
                """.formatted(DEFAULT_TABLE));

        List<Object> params = new ArrayList<>();
        params.add(query);
        params.add(query);
        appendFilters(sql, filters, params);

        sql.append(" ORDER BY similarity DESC LIMIT ?");
        params.add(limit);

        return jdbcTemplate.query(sql.toString(), new SearchResultRowMapper(DEFAULT_TABLE),
                params.toArray());
    }

    // ---- Internals ----

    private void appendFilters(StringBuilder sql, SearchFilters filters, List<Object> params) {
        if (filters == null) {
            return;
        }
        if (filters.hasRouteId()) {
            sql.append(" AND saved_route_id = ?::uuid");
            params.add(filters.routeId());
        }
        if (filters.timeFrom() != null) {
            sql.append(" AND time >= ?");
            params.add(Timestamp.from(filters.timeFrom()));
        }
        if (filters.timeTo() != null) {
            sql.append(" AND time <= ?");
            params.add(Timestamp.from(filters.timeTo()));
        }
        if (filters.hasMinRiskScore()) {
            sql.append(" AND risk_score >= ?");
            params.add(filters.minRiskScore());
        }
        if (filters.hasSeverity()) {
            sql.append(" AND risk_level = ?");
            params.add(filters.severity());
        }
        if (filters.hasTenantId()) {
            sql.append(" AND tenant_id = ?::uuid");
            params.add(filters.tenantId());
        }
    }

    private static String toVectorLiteral(float[] vector) {
        return java.util.stream.IntStream.range(0, vector.length)
                .mapToObj(i -> Float.toString(vector[i]))
                .collect(Collectors.joining(",", "[", "]"));
    }

    /** Rejects anything that is not a simple identifier to prevent SQL injection. */
    private static String sanitizeTableName(String name) {
        if (name == null || !name.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid table name: " + name);
        }
        return name;
    }

    private static final class SearchResultRowMapper implements RowMapper<SearchResult> {
        private final String tableName;

        SearchResultRowMapper(String tableName) {
            this.tableName = tableName;
        }

        @Override
        public SearchResult mapRow(ResultSet rs, int rowNum) throws SQLException {
            UUID id = rs.getObject("id", UUID.class);
            int riskScore = rs.getInt("risk_score");
            String factorsText = rs.getString("factors");
            Timestamp ts = rs.getTimestamp("time");
            Instant time = ts != null ? ts.toInstant() : null;
            UUID routeId = rs.getObject("saved_route_id", UUID.class);
            double similarity = rs.getDouble("similarity");

            return new SearchResult(id, tableName, similarity, riskScore,
                    factorsText, time, routeId, Map.of());
        }
    }
}
