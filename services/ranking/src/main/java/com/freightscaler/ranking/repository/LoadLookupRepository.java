package com.freightscaler.ranking.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Read-only lookup against load-board's freight_load table (shared database,
 * owned by the load-board service — ranking never writes to it).
 * Bid events carry only loadId; the corridor needed for corridor rankings is
 * resolved here.
 */
@Repository
public class LoadLookupRepository {

    private final JdbcTemplate jdbc;

    public LoadLookupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The corridor a load belongs to, empty if the load or its corridor is unknown.
     */
    public Optional<String> findCorridorByLoadId(Long loadId) {
        if (loadId == null) {
            return Optional.empty();
        }
        List<String> rows = jdbc.queryForList(
                "SELECT corridor_id FROM freight_load WHERE id = ?",
                String.class,
                loadId);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }
}
