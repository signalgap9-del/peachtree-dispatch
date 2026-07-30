package com.freightscaler.settlement.repository;

import com.freightscaler.settlement.model.Settlement;
import com.freightscaler.settlement.model.SettlementStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SettlementRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<Settlement> ROW_MAPPER = (rs, rowNum) -> new Settlement(
            rs.getLong("id"),
            rs.getLong("load_id"),
            rs.getLong("bid_id"),
            UUID.fromString(rs.getString("carrier_id")),
            UUID.fromString(rs.getString("shipper_id")),
            rs.getInt("base_rate_cents"),
            rs.getInt("adjustment_cents"),
            rs.getObject("final_amount_cents") != null ? rs.getInt("final_amount_cents") : null,
            SettlementStatus.valueOf(rs.getString("status")),
            rs.getInt("current_step"),
            rs.getString("saga_log"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("completed_at") != null ? rs.getTimestamp("completed_at").toInstant() : null
    );

    public SettlementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Settlement insert(Settlement s) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO settlement (load_id, bid_id, carrier_id, shipper_id,
                                           base_rate_cents, adjustment_cents, final_amount_cents,
                                           status, current_step, saga_log, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                    """,
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, s.loadId());
            ps.setLong(2, s.bidId());
            ps.setObject(3, s.carrierId());
            ps.setObject(4, s.shipperId());
            ps.setInt(5, s.baseRateCents());
            ps.setInt(6, s.adjustmentCents());
            if (s.finalAmountCents() != null) {
                ps.setInt(7, s.finalAmountCents());
            } else {
                ps.setNull(7, Types.INTEGER);
            }
            ps.setString(8, s.status().name());
            ps.setInt(9, s.currentStep());
            ps.setString(10, s.sagaLog() != null ? s.sagaLog() : "[]");
            ps.setTimestamp(11, Timestamp.from(s.createdAt()));
            return ps;
        }, keyHolder);

        Long generatedId = keyHolder.getKey().longValue();
        return new Settlement(
                generatedId, s.loadId(), s.bidId(), s.carrierId(), s.shipperId(),
                s.baseRateCents(), s.adjustmentCents(), s.finalAmountCents(),
                s.status(), s.currentStep(), s.sagaLog(), s.createdAt(), null
        );
    }

    public Optional<Settlement> findById(Long id) {
        List<Settlement> results = jdbc.query(
                "SELECT * FROM settlement WHERE id = ?", ROW_MAPPER, id);
        return results.stream().findFirst();
    }

    public Optional<Settlement> findByLoadId(Long loadId) {
        List<Settlement> results = jdbc.query(
                "SELECT * FROM settlement WHERE load_id = ? ORDER BY created_at DESC LIMIT 1",
                ROW_MAPPER, loadId);
        return results.stream().findFirst();
    }

    /**
     * Keyset pagination for carrier view: cursor = last seen settlement id.
     */
    public List<Settlement> findByCarrier(UUID carrierId, String status, Long cursor, int limit) {
        if (status != null && cursor != null) {
            return jdbc.query(
                    "SELECT * FROM settlement WHERE carrier_id = ? AND status = ? AND id < ? ORDER BY id DESC LIMIT ?",
                    ROW_MAPPER, carrierId, status, cursor, limit);
        } else if (status != null) {
            return jdbc.query(
                    "SELECT * FROM settlement WHERE carrier_id = ? AND status = ? ORDER BY id DESC LIMIT ?",
                    ROW_MAPPER, carrierId, status, limit);
        } else if (cursor != null) {
            return jdbc.query(
                    "SELECT * FROM settlement WHERE carrier_id = ? AND id < ? ORDER BY id DESC LIMIT ?",
                    ROW_MAPPER, carrierId, cursor, limit);
        }
        return jdbc.query(
                "SELECT * FROM settlement WHERE carrier_id = ? ORDER BY id DESC LIMIT ?",
                ROW_MAPPER, carrierId, limit);
    }

    /**
     * Keyset pagination for shipper view: cursor = last seen settlement id.
     */
    public List<Settlement> findByShipper(UUID shipperId, String status, Long cursor, int limit) {
        if (status != null && cursor != null) {
            return jdbc.query(
                    "SELECT * FROM settlement WHERE shipper_id = ? AND status = ? AND id < ? ORDER BY id DESC LIMIT ?",
                    ROW_MAPPER, shipperId, status, cursor, limit);
        } else if (status != null) {
            return jdbc.query(
                    "SELECT * FROM settlement WHERE shipper_id = ? AND status = ? ORDER BY id DESC LIMIT ?",
                    ROW_MAPPER, shipperId, status, limit);
        } else if (cursor != null) {
            return jdbc.query(
                    "SELECT * FROM settlement WHERE shipper_id = ? AND id < ? ORDER BY id DESC LIMIT ?",
                    ROW_MAPPER, shipperId, cursor, limit);
        }
        return jdbc.query(
                "SELECT * FROM settlement WHERE shipper_id = ? ORDER BY id DESC LIMIT ?",
                ROW_MAPPER, shipperId, limit);
    }

    public int updateStatus(Long id, SettlementStatus status, int currentStep, String sagaLog) {
        return jdbc.update(
                "UPDATE settlement SET status = ?, current_step = ?, saga_log = ?::jsonb WHERE id = ?",
                status.name(), currentStep, sagaLog, id);
    }

    public int updateFinalAmount(Long id, int adjustmentCents, int finalAmountCents) {
        return jdbc.update(
                "UPDATE settlement SET adjustment_cents = ?, final_amount_cents = ? WHERE id = ?",
                adjustmentCents, finalAmountCents, id);
    }

    public int markCompleted(Long id, String sagaLog) {
        return jdbc.update(
                "UPDATE settlement SET status = 'COMPLETED', current_step = 5, saga_log = ?::jsonb, completed_at = now() WHERE id = ?",
                sagaLog, id);
    }

    public int markFailed(Long id, SettlementStatus status, int currentStep, String sagaLog) {
        return jdbc.update(
                "UPDATE settlement SET status = ?, current_step = ?, saga_log = ?::jsonb, completed_at = now() WHERE id = ?",
                status.name(), currentStep, sagaLog, id);
    }

    /**
     * Records an individual step execution in the settlement_step_log audit table.
     */
    public void insertStepLog(Long settlementId, int stepNumber, String stepName, String status, String detailJson) {
        jdbc.update(
                "INSERT INTO settlement_step_log (settlement_id, step_number, step_name, status, detail) VALUES (?, ?, ?, ?, ?::jsonb)",
                settlementId, stepNumber, stepName, status,
                detailJson != null ? detailJson : "{}");
    }
}
