package com.freightscaler.ranking.repository;

import com.freightscaler.ranking.model.RankingSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * JDBC access to ranking_snapshot (owned by this service, V021).
 * Snapshots are written in batches; history queries are served by the
 * (category, corridor_id, snapshot_time) and (carrier_id, snapshot_time) indexes.
 */
@Repository
public class RankingSnapshotRepository {

    private static final String INSERT_SQL =
            "INSERT INTO ranking_snapshot (snapshot_time, category, corridor_id, carrier_id, score, rank) " +
            "VALUES (?, ?, ?, ?, ?, ?)";

    private static final int BATCH_CHUNK_SIZE = 500;

    private static final RowMapper<RankingSnapshot> ROW_MAPPER = (rs, rowNum) -> new RankingSnapshot(
            rs.getTimestamp("snapshot_time").toInstant(),
            rs.getString("category"),
            rs.getString("corridor_id"),
            rs.getObject("carrier_id", UUID.class),
            rs.getDouble("score"),
            rs.getInt("rank")
    );

    private final JdbcTemplate jdbc;

    public RankingSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Bulk INSERT of one snapshot cycle using JDBC batching.
     */
    public void batchInsertSnapshots(List<RankingSnapshot> snapshots) {
        jdbc.batchUpdate(INSERT_SQL, snapshots, BATCH_CHUNK_SIZE,
                (PreparedStatement ps, RankingSnapshot s) -> {
                    ps.setTimestamp(1, Timestamp.from(s.snapshotTime()));
                    ps.setString(2, s.category());
                    ps.setString(3, s.corridorId());
                    ps.setObject(4, s.carrierId());
                    ps.setDouble(5, s.score());
                    ps.setInt(6, s.rank());
                });
    }

    /**
     * Historical scores, newest first. corridorId may be null, which selects
     * overall snapshots (IS NOT DISTINCT FROM matches NULL safely).
     */
    public List<RankingSnapshot> findHistory(String category, String corridorId, UUID carrierId, int limit) {
        return jdbc.query(
                "SELECT snapshot_time, category, corridor_id, carrier_id, score, rank " +
                "FROM ranking_snapshot " +
                "WHERE category = ? AND carrier_id = ? AND corridor_id IS NOT DISTINCT FROM ? " +
                "ORDER BY snapshot_time DESC LIMIT ?",
                ROW_MAPPER, category, carrierId, corridorId, limit);
    }
}
