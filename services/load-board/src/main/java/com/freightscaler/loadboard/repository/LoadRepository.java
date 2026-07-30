package com.freightscaler.loadboard.repository;

import com.freightscaler.loadboard.model.CargoType;
import com.freightscaler.loadboard.model.Load;
import com.freightscaler.loadboard.model.LoadStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class LoadRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<Load> ROW_MAPPER = (rs, rowNum) -> new Load(
            rs.getLong("id"),
            rs.getObject("tenant_id", java.util.UUID.class),
            rs.getString("origin"),
            rs.getString("destination"),
            CargoType.valueOf(rs.getString("cargo_type")),
            rs.getObject("weight_kg", Integer.class),
            rs.getObject("pickup_start", Timestamp.class) != null
                    ? rs.getTimestamp("pickup_start").toInstant() : null,
            rs.getObject("pickup_end", Timestamp.class) != null
                    ? rs.getTimestamp("pickup_end").toInstant() : null,
            rs.getObject("delivery_deadline", Timestamp.class) != null
                    ? rs.getTimestamp("delivery_deadline").toInstant() : null,
            rs.getObject("max_rate_cents", Integer.class),
            rs.getString("corridor_id"),
            rs.getObject("corridor_risk", Short.class),
            LoadStatus.valueOf(rs.getString("status")),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    public LoadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(Load load) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO freight_load
                        (tenant_id, origin, destination, cargo_type, weight_kg,
                         pickup_start, pickup_end, delivery_deadline,
                         max_rate_cents, corridor_id, corridor_risk, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    new String[] { "id" });
            ps.setObject(1, load.tenantId());
            ps.setString(2, load.origin());
            ps.setString(3, load.destination());
            ps.setString(4, load.cargoType().name());
            ps.setObject(5, load.weightKg());
            ps.setTimestamp(6, toTimestamp(load.pickupStart()));
            ps.setTimestamp(7, toTimestamp(load.pickupEnd()));
            ps.setTimestamp(8, toTimestamp(load.deliveryDeadline()));
            ps.setObject(9, load.maxRateCents());
            ps.setString(10, load.corridorId());
            ps.setObject(11, load.corridorRisk());
            ps.setString(12, load.status().name());
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<Load> findById(Long id) {
        List<Load> results = jdbc.query(
                "SELECT * FROM freight_load WHERE id = ?", ROW_MAPPER, id);
        return results.stream().findFirst();
    }

    public List<Load> findOpen(String corridorId, String cargoType, Long cursor, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM freight_load WHERE status = 'OPEN'");
        List<Object> params = new ArrayList<>();

        if (cursor != null) {
            sql.append(" AND id < ?");
            params.add(cursor);
        }
        if (corridorId != null && !corridorId.isBlank()) {
            sql.append(" AND corridor_id = ?");
            params.add(corridorId);
        }
        if (cargoType != null && !cargoType.isBlank()) {
            sql.append(" AND cargo_type = ?");
            params.add(cargoType);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        params.add(limit);

        return jdbc.query(sql.toString(), ROW_MAPPER, params.toArray());
    }

    /**
     * Optimistic-lock status transition.
     *
     * @return the new version number, or -1 if the row was not found or the
     *         expected version did not match (conflict).
     */
    public int updateStatus(Long id, LoadStatus newStatus, int expectedVersion) {
        List<Integer> versions = jdbc.query(
                """
                UPDATE freight_load
                   SET status = ?, version = version + 1, updated_at = now()
                 WHERE id = ? AND version = ?
                RETURNING version
                """,
                (rs, rowNum) -> rs.getInt("version"),
                newStatus.name(), id, expectedVersion);
        return versions.isEmpty() ? -1 : versions.get(0);
    }

    public long countByStatus(LoadStatus status) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM freight_load WHERE status = ?",
                Long.class, status.name());
        return count != null ? count : 0L;
    }

    private static Timestamp toTimestamp(java.time.Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }
}
