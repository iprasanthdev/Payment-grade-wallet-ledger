package com.airtribe.wallet.repository;

import com.airtribe.wallet.model.TransferAuditLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class AuditLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public AuditLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<TransferAuditLog> rowMapper = (rs, rowNum) -> new TransferAuditLog(
            rs.getLong("id"),
            rs.getLong("transfer_id"),
            rs.getString("from_status"),
            rs.getString("to_status"),
            rs.getString("changed_by"),
            rs.getString("reason"),
            rs.getTimestamp("created_at").toInstant()
    );

    public void logStatusChange(Long transferId, String fromStatus, String toStatus, String changedBy, String reason) {
        String sql = """
            INSERT INTO transfer_audit_logs (transfer_id, from_status, to_status, changed_by, reason)
            VALUES (?, ?, ?, ?, ?)
            """;
        jdbcTemplate.update(sql, transferId, fromStatus, toStatus, changedBy, reason);
    }

    public List<TransferAuditLog> findByTransferId(Long transferId) {
        String sql = "SELECT id, transfer_id, from_status, to_status, changed_by, reason, created_at FROM transfer_audit_logs WHERE transfer_id = ? ORDER BY id ASC";
        return jdbcTemplate.query(sql, rowMapper, transferId);
    }
}
