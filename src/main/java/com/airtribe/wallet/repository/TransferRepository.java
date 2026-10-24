package com.airtribe.wallet.repository;

import com.airtribe.wallet.model.Transfer;
import com.airtribe.wallet.model.TransferStatus;
import com.airtribe.wallet.model.TransferType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class TransferRepository {

    private final JdbcTemplate jdbcTemplate;

    public TransferRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<Transfer> rowMapper = (rs, rowNum) -> new Transfer(
            rs.getLong("id"),
            TransferType.valueOf(rs.getString("transfer_type")),
            TransferStatus.valueOf(rs.getString("status")),
            rs.getLong("amount"),
            rs.getLong("source_account_id"),
            rs.getLong("destination_account_id"),
            rs.getObject("related_transfer_id") != null ? rs.getLong("related_transfer_id") : null,
            rs.getString("note"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    public Transfer createTransfer(
            TransferType transferType,
            TransferStatus status,
            long amount,
            Long sourceAccountId,
            Long destinationAccountId,
            Long relatedTransferId,
            String note
    ) {
        String sql = """
            INSERT INTO transfers (transfer_type, status, amount, source_account_id, destination_account_id, related_transfer_id, note)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            RETURNING id, transfer_type, status, amount, source_account_id, destination_account_id, related_transfer_id, note, created_at, updated_at
            """;
        return jdbcTemplate.queryForObject(
                sql,
                rowMapper,
                transferType.name(),
                status.name(),
                amount,
                sourceAccountId,
                destinationAccountId,
                relatedTransferId,
                note
        );
    }

    public void updateStatus(Long transferId, TransferStatus newStatus) {
        String sql = "UPDATE transfers SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        jdbcTemplate.update(sql, newStatus.name(), transferId);
    }

    public Optional<Transfer> findById(Long transferId) {
        String sql = "SELECT id, transfer_type, status, amount, source_account_id, destination_account_id, related_transfer_id, note, created_at, updated_at FROM transfers WHERE id = ?";
        return jdbcTemplate.query(sql, rowMapper, transferId).stream().findFirst();
    }

    public List<Transfer> findRecentTransfersForAccount(Long accountId, int limit) {
        String sql = """
            SELECT id, transfer_type, status, amount, source_account_id, destination_account_id, related_transfer_id, note, created_at, updated_at
            FROM transfers
            WHERE source_account_id = ? OR destination_account_id = ?
            ORDER BY created_at DESC, id DESC
            LIMIT ?
            """;
        return jdbcTemplate.query(sql, rowMapper, accountId, accountId, limit);
    }

    public int countTransfersInLastSeconds(Long sourceAccountId, int seconds) {
        String sql = "SELECT COUNT(*) FROM transfers WHERE source_account_id = ? AND created_at >= (CURRENT_TIMESTAMP - (? || ' seconds')::INTERVAL)";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, sourceAccountId, String.valueOf(seconds));
        return count != null ? count : 0;
    }
}
