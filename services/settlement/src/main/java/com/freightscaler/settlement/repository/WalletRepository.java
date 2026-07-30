package com.freightscaler.settlement.repository;

import com.freightscaler.settlement.model.Wallet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class WalletRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<Wallet> ROW_MAPPER = (rs, rowNum) -> new Wallet(
            UUID.fromString(rs.getString("owner_id")),
            rs.getLong("balance_cents"),
            rs.getInt("version"),
            rs.getTimestamp("updated_at").toInstant()
    );

    public WalletRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Atomic debit: only succeeds if balance >= amount.
     * The WHERE clause prevents negative balances without a separate SELECT lock.
     *
     * @return number of rows updated (1 = success, 0 = insufficient funds)
     */
    public int debit(UUID ownerId, long amountCents) {
        return jdbc.update(
                """
                UPDATE wallet
                SET balance_cents = balance_cents - ?,
                    version = version + 1,
                    updated_at = now()
                WHERE owner_id = ? AND balance_cents >= ?
                """,
                amountCents, ownerId, amountCents);
    }

    /**
     * Credit always succeeds (no upper-bound check).
     *
     * @return number of rows updated (1 = success, 0 = wallet not found)
     */
    public int credit(UUID ownerId, long amountCents) {
        return jdbc.update(
                """
                UPDATE wallet
                SET balance_cents = balance_cents + ?,
                    version = version + 1,
                    updated_at = now()
                WHERE owner_id = ?
                """,
                amountCents, ownerId);
    }

    public Optional<Wallet> findById(UUID ownerId) {
        List<Wallet> results = jdbc.query(
                "SELECT * FROM wallet WHERE owner_id = ?", ROW_MAPPER, ownerId);
        return results.stream().findFirst();
    }

    public int insert(UUID ownerId, long initialBalanceCents) {
        return jdbc.update(
                "INSERT INTO wallet (owner_id, balance_cents) VALUES (?, ?) ON CONFLICT (owner_id) DO NOTHING",
                ownerId, initialBalanceCents);
    }
}
