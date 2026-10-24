package com.airtribe.wallet.repository;

import com.airtribe.wallet.model.ReconciliationDiscrepancy;
import com.airtribe.wallet.model.ReconciliationRun;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class ReconciliationRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReconciliationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<ReconciliationRun> runRowMapper = (rs, rowNum) -> new ReconciliationRun(
            rs.getLong("id"),
            rs.getTimestamp("started_at").toInstant(),
            rs.getTimestamp("ended_at") != null ? rs.getTimestamp("ended_at").toInstant() : null,
            rs.getString("status"),
            rs.getLong("total_debits"),
            rs.getLong("total_credits"),
            rs.getLong("transfers_checked"),
            rs.getLong("accounts_checked"),
            rs.getInt("discrepancy_count")
    );

    private final RowMapper<ReconciliationDiscrepancy> discrepancyRowMapper = (rs, rowNum) -> new ReconciliationDiscrepancy(
            rs.getLong("id"),
            rs.getLong("run_id"),
            rs.getString("check_name"),
            rs.getObject("entity_id") != null ? rs.getLong("entity_id") : null,
            rs.getString("expected_value"),
            rs.getString("actual_value"),
            rs.getString("details"),
            rs.getTimestamp("detected_at").toInstant()
    );

    /**
     * Attempts to acquire job lock using SELECT ... FOR UPDATE NOWAIT on job_locks table.
     * Throws CannotAcquireLockException if another run holds the lock.
     */
    public boolean tryAcquireJobLock(String jobName, String lockedBy) {
        try {
            String sql = "SELECT job_name FROM job_locks WHERE job_name = ? FOR UPDATE NOWAIT";
            jdbcTemplate.queryForObject(sql, String.class, jobName);
            jdbcTemplate.update("UPDATE job_locks SET locked_at = CURRENT_TIMESTAMP, locked_by = ? WHERE job_name = ?", lockedBy, jobName);
            return true;
        } catch (CannotAcquireLockException e) {
            return false;
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("could not obtain lock")) {
                return false;
            }
            throw e;
        }
    }

    public void releaseJobLock(String jobName) {
        jdbcTemplate.update("UPDATE job_locks SET locked_at = NULL, locked_by = NULL WHERE job_name = ?", jobName);
    }

    public Long createRun(Instant startedAt) {
        String sql = "INSERT INTO reconciliation_runs (started_at, status) VALUES (?, 'RUNNING') RETURNING id";
        return jdbcTemplate.queryForObject(sql, Long.class, java.sql.Timestamp.from(startedAt));
    }

    public void completeRun(
            Long runId,
            String status,
            long totalDebits,
            long totalCredits,
            long transfersChecked,
            long accountsChecked,
            int discrepancyCount
    ) {
        String sql = """
            UPDATE reconciliation_runs
            SET ended_at = CURRENT_TIMESTAMP,
                status = ?,
                total_debits = ?,
                total_credits = ?,
                transfers_checked = ?,
                accounts_checked = ?,
                discrepancy_count = ?
            WHERE id = ?
            """;
        jdbcTemplate.update(sql, status, totalDebits, totalCredits, transfersChecked, accountsChecked, discrepancyCount, runId);
    }

    public void recordDiscrepancy(Long runId, String checkName, Long entityId, String expected, String actual, String details) {
        String sql = """
            INSERT INTO reconciliation_discrepancies (run_id, check_name, entity_id, expected_value, actual_value, details)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
        jdbcTemplate.update(sql, runId, checkName, entityId, expected, actual, details);
    }

    // =========================================================================
    // AUDIT CHECKS (Executed in REPEATABLE READ transaction)
    // =========================================================================

    public record TotalBalanceResult(long totalDebits, long totalCredits) {}

    /**
     * Check 1: Everything balances - sum of all debits equals sum of all credits.
     */
    public TotalBalanceResult checkTotalBalance() {
        String sql = """
            SELECT 
                COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE 0 END), 0) AS total_debits,
                COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE 0 END), 0) AS total_credits
            FROM ledger_entries
            """;
        return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new TotalBalanceResult(
                rs.getLong("total_debits"),
                rs.getLong("total_credits")
        ));
    }

    public record UnbalancedTransfer(Long transferId, long debits, long credits) {}

    /**
     * Check 2: Every transfer balances - each transfer's debits equal its credits.
     */
    public List<UnbalancedTransfer> checkTransferBalances() {
        String sql = """
            SELECT 
                transfer_id,
                SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE 0 END) AS debits,
                SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE 0 END) AS credits
            FROM ledger_entries
            GROUP BY transfer_id
            HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE 0 END) != SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE 0 END)
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new UnbalancedTransfer(
                rs.getLong("transfer_id"),
                rs.getLong("debits"),
                rs.getLong("credits")
        ));
    }

    public record AccountBalanceMismatch(Long accountId, long storedBalance, long computedBalance) {}

    /**
     * Check 3: Stored balances match computed ledger sums.
     */
    public List<AccountBalanceMismatch> checkStoredVsComputedBalances() {
        String sql = """
            SELECT 
                a.id AS account_id,
                a.balance AS stored_balance,
                COALESCE(SUM(CASE WHEN le.direction = 'CREDIT' THEN le.amount ELSE -le.amount END), 0) AS computed_balance
            FROM accounts a
            LEFT JOIN ledger_entries le ON a.id = le.account_id
            GROUP BY a.id, a.balance
            HAVING a.balance != COALESCE(SUM(CASE WHEN le.direction = 'CREDIT' THEN le.amount ELSE -le.amount END), 0)
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new AccountBalanceMismatch(
                rs.getLong("account_id"),
                rs.getLong("stored_balance"),
                rs.getLong("computed_balance")
        ));
    }

    public record OverdraftAccount(Long accountId, long computedBalance) {}

    /**
     * Check 4: No user wallet's computed balance is below zero.
     */
    public List<OverdraftAccount> checkNoOverdrafts() {
        String sql = """
            SELECT 
                a.id AS account_id,
                COALESCE(SUM(CASE WHEN le.direction = 'CREDIT' THEN le.amount ELSE -le.amount END), 0) AS computed_balance
            FROM accounts a
            LEFT JOIN ledger_entries le ON a.id = le.account_id
            WHERE a.user_id IS NOT NULL
            GROUP BY a.id
            HAVING COALESCE(SUM(CASE WHEN le.direction = 'CREDIT' THEN le.amount ELSE -le.amount END), 0) < 0
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new OverdraftAccount(
                rs.getLong("account_id"),
                rs.getLong("computed_balance")
        ));
    }

    public record OrphanRecord(String type, Long id, String details) {}

    /**
     * Check 5: Nothing is orphaned: every transfer has entries, and every entry has a transfer.
     */
    public List<OrphanRecord> checkOrphans() {
        String sql = """
            -- Transfers with zero entries
            SELECT 'TRANSFER_WITHOUT_ENTRIES' AS orphan_type, t.id AS entity_id, 'Transfer ' || t.id || ' has no ledger entries' AS details
            FROM transfers t
            LEFT JOIN ledger_entries le ON t.id = le.transfer_id
            WHERE le.id IS NULL
            UNION ALL
            -- Ledger entries pointing to nonexistent transfer
            SELECT 'ENTRY_WITHOUT_TRANSFER' AS orphan_type, le.id AS entity_id, 'Entry ' || le.id || ' references non-existent transfer ' || le.transfer_id AS details
            FROM ledger_entries le
            LEFT JOIN transfers t ON le.transfer_id = t.id
            WHERE t.id IS NULL
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new OrphanRecord(
                rs.getString("orphan_type"),
                rs.getLong("entity_id"),
                rs.getString("details")
        ));
    }

    public long countTotalTransfers() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transfers", Long.class);
        return count != null ? count : 0L;
    }

    public long countTotalAccounts() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM accounts", Long.class);
        return count != null ? count : 0L;
    }

    public List<ReconciliationRun> getRecentRuns(int limit) {
        String sql = "SELECT id, started_at, ended_at, status, total_debits, total_credits, transfers_checked, accounts_checked, discrepancy_count FROM reconciliation_runs ORDER BY id DESC LIMIT ?";
        return jdbcTemplate.query(sql, runRowMapper, limit);
    }

    public List<ReconciliationDiscrepancy> getDiscrepanciesForRun(Long runId) {
        String sql = "SELECT id, run_id, check_name, entity_id, expected_value, actual_value, details, detected_at FROM reconciliation_discrepancies WHERE run_id = ? ORDER BY id ASC";
        return jdbcTemplate.query(sql, discrepancyRowMapper, runId);
    }
}
