package com.airtribe.wallet.repository;

import com.airtribe.wallet.model.LedgerDirection;
import com.airtribe.wallet.model.LedgerEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class LedgerEntryRepository {

    private final JdbcTemplate jdbcTemplate;

    public LedgerEntryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<LedgerEntry> rowMapper = (rs, rowNum) -> new LedgerEntry(
            rs.getLong("id"),
            rs.getLong("transfer_id"),
            rs.getLong("account_id"),
            LedgerDirection.valueOf(rs.getString("direction")),
            rs.getLong("amount"),
            rs.getTimestamp("created_at").toInstant()
    );

    public LedgerEntry createEntry(Long transferId, Long accountId, LedgerDirection direction, long amount) {
        String sql = """
            INSERT INTO ledger_entries (transfer_id, account_id, direction, amount)
            VALUES (?, ?, ?, ?)
            RETURNING id, transfer_id, account_id, direction, amount, created_at
            """;
        return jdbcTemplate.queryForObject(
                sql,
                rowMapper,
                transferId,
                accountId,
                direction.name(),
                amount
        );
    }

    /**
     * Offset-based pagination: ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?
     */
    public List<LedgerEntry> findByAccountIdOffset(Long accountId, int limit, int offset) {
        String sql = """
            SELECT id, transfer_id, account_id, direction, amount, created_at
            FROM ledger_entries
            WHERE account_id = ?
            ORDER BY created_at DESC, id DESC
            LIMIT ? OFFSET ?
            """;
        return jdbcTemplate.query(sql, rowMapper, accountId, limit, offset);
    }

    /**
     * Cursor-based pagination: using (id < cursorId) with ORDER BY id DESC LIMIT ?
     */
    public List<LedgerEntry> findByAccountIdCursor(Long accountId, Long cursorId, int limit) {
        if (cursorId == null) {
            String sql = """
                SELECT id, transfer_id, account_id, direction, amount, created_at
                FROM ledger_entries
                WHERE account_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """;
            return jdbcTemplate.query(sql, rowMapper, accountId, limit);
        } else {
            String sql = """
                SELECT id, transfer_id, account_id, direction, amount, created_at
                FROM ledger_entries
                WHERE account_id = ? AND id < ?
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """;
            return jdbcTemplate.query(sql, rowMapper, accountId, cursorId, limit);
        }
    }

    public long countByAccountId(Long accountId) {
        String sql = "SELECT COUNT(*) FROM ledger_entries WHERE account_id = ?";
        Long count = jdbcTemplate.queryForObject(sql, Long.class, accountId);
        return count != null ? count : 0L;
    }

    /**
     * Single source of financial truth: computes balance directly from immutable ledger lines.
     */
    public long computeBalanceForAccount(Long accountId) {
        String sql = """
            SELECT COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END), 0)
            FROM ledger_entries
            WHERE account_id = ?
            """;
        Long balance = jdbcTemplate.queryForObject(sql, Long.class, accountId);
        return balance != null ? balance : 0L;
    }
}
