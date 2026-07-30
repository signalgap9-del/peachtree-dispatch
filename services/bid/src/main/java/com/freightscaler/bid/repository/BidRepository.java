package com.freightscaler.bid.repository;

import com.freightscaler.bid.model.Bid;
import com.freightscaler.bid.model.BidStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class BidRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<Bid> ROW_MAPPER = (rs, rowNum) -> new Bid(
            rs.getLong("id"),
            rs.getLong("load_id"),
            UUID.fromString(rs.getString("carrier_id")),
            rs.getInt("rate_cents"),
            rs.getObject("estimated_hours") != null ? rs.getDouble("estimated_hours") : null,
            rs.getBoolean("risk_ack"),
            BidStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toInstant()
    );

    public BidRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Bid insert(Bid bid) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO bid (load_id, carrier_id, rate_cents, estimated_hours, risk_ack, status, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    new String[] { "id" }
            );
            ps.setLong(1, bid.loadId());
            ps.setObject(2, bid.carrierId());
            ps.setInt(3, bid.rateCents());
            if (bid.estimatedHours() != null) {
                ps.setDouble(4, bid.estimatedHours());
            } else {
                ps.setNull(4, java.sql.Types.REAL);
            }
            ps.setBoolean(5, bid.riskAcknowledgment());
            ps.setString(6, bid.status().name());
            ps.setTimestamp(7, Timestamp.from(bid.createdAt()));
            return ps;
        }, keyHolder);

        Long generatedId = keyHolder.getKey().longValue();
        return new Bid(
                generatedId,
                bid.loadId(),
                bid.carrierId(),
                bid.rateCents(),
                bid.estimatedHours(),
                bid.riskAcknowledgment(),
                bid.status(),
                bid.createdAt()
        );
    }

    public Optional<Bid> findById(Long id) {
        List<Bid> results = jdbc.query("SELECT * FROM bid WHERE id = ?", ROW_MAPPER, id);
        return results.stream().findFirst();
    }

    public List<Bid> findByLoadId(Long loadId) {
        return jdbc.query(
                "SELECT * FROM bid WHERE load_id = ? ORDER BY created_at ASC",
                ROW_MAPPER, loadId
        );
    }

    public List<Bid> findByCarrierId(UUID carrierId, Long cursor, int limit) {
        if (cursor != null) {
            return jdbc.query(
                    "SELECT * FROM bid WHERE carrier_id = ? AND id < ? ORDER BY id DESC LIMIT ?",
                    ROW_MAPPER, carrierId, cursor, limit
            );
        }
        return jdbc.query(
                "SELECT * FROM bid WHERE carrier_id = ? ORDER BY id DESC LIMIT ?",
                ROW_MAPPER, carrierId, limit
        );
    }

    public int updateStatus(Long id, BidStatus status) {
        return jdbc.update(
                "UPDATE bid SET status = ? WHERE id = ?",
                status.name(), id
        );
    }

    public int rejectOthersOnLoad(Long loadId, Long acceptedBidId) {
        return jdbc.update(
                "UPDATE bid SET status = 'REJECTED' WHERE load_id = ? AND id != ? AND status = 'SUBMITTED'",
                loadId, acceptedBidId
        );
    }

    /**
     * Optimistic-lock accept: atomically transitions the freight_load to MATCHED
     * only if the version hasn't changed and the load is still OPEN.
     *
     * @return true if exactly one row was updated (success), false on conflict
     */
    public boolean acceptWithOptimisticLock(Long bidId) {
        int rows = jdbc.update(
                """
                UPDATE freight_load
                SET status = 'MATCHED', version = version + 1
                WHERE id = (SELECT load_id FROM bid WHERE id = ?)
                  AND version = (SELECT version FROM freight_load fl WHERE fl.id = (SELECT load_id FROM bid WHERE id = ?))
                  AND status = 'OPEN'
                """,
                bidId, bidId
        );
        return rows > 0;
    }
}
