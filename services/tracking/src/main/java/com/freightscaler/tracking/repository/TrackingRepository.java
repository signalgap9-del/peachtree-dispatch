package com.freightscaler.tracking.repository;

import com.freightscaler.tracking.model.TrackingEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * JDBC repository for tracking_event hypertable.
 * Uses batch INSERT for high-throughput ingestion and keyset pagination for history queries.
 */
@Repository
public class TrackingRepository {

    private static final String INSERT_SQL =
            "INSERT INTO tracking_event (time, truck_id, corridor_id, lat, lon, speed_kmh, heading) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)";

    private static final RowMapper<TrackingEvent> ROW_MAPPER = (rs, rowNum) -> new TrackingEvent(
            rs.getTimestamp("time").toInstant(),
            UUID.fromString(rs.getString("truck_id")),
            rs.getString("corridor_id"),
            rs.getDouble("lat"),
            rs.getDouble("lon"),
            rs.getObject("speed_kmh") != null ? rs.getDouble("speed_kmh") : null,
            rs.getObject("heading") != null ? rs.getInt("heading") : null,
            rs.getObject("risk_score") != null ? rs.getShort("risk_score") : null
    );

    private final JdbcTemplate jdbc;

    public TrackingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Bulk INSERT using JDBC batch for high throughput.
     */
    public void batchInsert(List<TrackingEvent> events) {
        jdbc.batchUpdate(INSERT_SQL, events, events.size(), (PreparedStatement ps, TrackingEvent e) -> {
            ps.setTimestamp(1, Timestamp.from(e.time()));
            ps.setObject(2, e.truckId());
            ps.setString(3, e.corridorId());
            ps.setDouble(4, e.lat());
            ps.setDouble(5, e.lon());
            if (e.speedKmh() != null) {
                ps.setDouble(6, e.speedKmh());
            } else {
                ps.setNull(6, java.sql.Types.REAL);
            }
            if (e.heading() != null) {
                ps.setInt(7, e.heading());
            } else {
                ps.setNull(7, java.sql.Types.SMALLINT);
            }
        });
    }

    /**
     * Keyset pagination: fetch history for a truck, optionally after a cursor (epoch millis).
     * Returns events ordered by time DESC.
     */
    public List<TrackingEvent> findByTruckId(UUID truckId, Long cursor, int limit) {
        if (cursor == null) {
            return jdbc.query(
                    "SELECT time, truck_id, corridor_id, lat, lon, speed_kmh, heading, risk_score " +
                    "FROM tracking_event WHERE truck_id = ? ORDER BY time DESC LIMIT ?",
                    ROW_MAPPER, truckId, limit);
        }
        return jdbc.query(
                "SELECT time, truck_id, corridor_id, lat, lon, speed_kmh, heading, risk_score " +
                "FROM tracking_event WHERE truck_id = ? AND time < to_timestamp(?::double precision / 1000) " +
                "ORDER BY time DESC LIMIT ?",
                ROW_MAPPER, truckId, cursor, limit);
    }

    /**
     * Active trucks on a corridor in the last 5 minutes.
     */
    public List<TrackingEvent> findActiveByCorridor(String corridorId) {
        return jdbc.query(
                "SELECT DISTINCT ON (truck_id) time, truck_id, corridor_id, lat, lon, speed_kmh, heading, risk_score " +
                "FROM tracking_event " +
                "WHERE corridor_id = ? AND time > now() - interval '5 minutes' " +
                "ORDER BY truck_id, time DESC",
                ROW_MAPPER, corridorId);
    }

    /**
     * Count distinct active trucks on a corridor in the last 5 minutes.
     */
    public long countByCorridor(String corridorId) {
        Long count = jdbc.queryForObject(
                "SELECT count(DISTINCT truck_id) FROM tracking_event " +
                "WHERE corridor_id = ? AND time > now() - interval '5 minutes'",
                Long.class, corridorId);
        return count != null ? count : 0;
    }
}
