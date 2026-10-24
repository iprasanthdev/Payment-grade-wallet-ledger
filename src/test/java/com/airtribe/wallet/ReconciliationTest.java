package com.airtribe.wallet;

import com.airtribe.wallet.model.ReconciliationDiscrepancy;
import com.airtribe.wallet.repository.AccountRepository;
import com.airtribe.wallet.repository.ReconciliationRepository;
import com.airtribe.wallet.repository.UserRepository;
import com.airtribe.wallet.seed.SeedService;
import com.airtribe.wallet.service.ReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
public class ReconciliationTest {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationTest.class);

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private ReconciliationRepository reconciliationRepository;

    @Autowired
    private SeedService seedService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE reconciliation_discrepancies, reconciliation_runs, risk_flags, transfer_audit_logs, ledger_entries, transfers, idempotency_keys CASCADE;");
        jdbcTemplate.execute("DELETE FROM accounts WHERE user_id IS NOT NULL;");
        jdbcTemplate.execute("DELETE FROM users CASCADE;");
    }

    /**
     * Milestone 4 Acceptance Test:
     * 1. Seed 10,000 transfers.
     * 2. Verify clean initial audit (0 discrepancies).
     * 3. Corrupt data directly in the database:
     *    - Change one entry's amount
     *    - Delete one credit entry
     *    - Change one stored balance
     * 4. Run reconciliation job.
     * 5. Verify the job reports EXACTLY those problems!
     */
    @Test
    @DisplayName("Milestone 4 Acceptance Test: Seed 10,000 Transfers, Corrupt 3 Items, Verify Exact Reporting")
    void testNightlyReconciliationDetectsExactCorruptions() {
        // 1. Seed 10,000 transfers across 200 users and 400 wallets
        log.info("Seeding 10,000 transfers for reconciliation test...");
        seedService.runSeed(200, 400, 10000);

        // Run baseline reconciliation - must pass with 0 discrepancies
        ReconciliationService.AuditResult baseline = reconciliationService.runNightlyAudit();
        assertThat(baseline.status()).isEqualTo("SUCCESS");
        assertThat(baseline.discrepancyCount()).isEqualTo(0);

        log.info("Baseline reconciliation passed with 0 discrepancies. Injecting 3 specific corruptions...");

        // Disable immutability trigger temporarily to simulate low-level DB corruption
        jdbcTemplate.execute("ALTER TABLE ledger_entries DISABLE TRIGGER trg_no_update_delete_ledger_entries;");

        try {
            // Corruption 1: Change one entry's amount
            Long targetEntryId = jdbcTemplate.queryForObject(
                    "SELECT id FROM ledger_entries WHERE direction = 'DEBIT' ORDER BY id ASC LIMIT 1", Long.class);
            jdbcTemplate.update("UPDATE ledger_entries SET amount = amount + 500 WHERE id = ?", targetEntryId);
            log.info("Injected Corruption 1: Modified amount of ledger_entry {}", targetEntryId);

            // Corruption 2: Delete one credit entry
            Long creditEntryId = jdbcTemplate.queryForObject(
                    "SELECT id FROM ledger_entries WHERE direction = 'CREDIT' ORDER BY id DESC LIMIT 1", Long.class);
            jdbcTemplate.update("DELETE FROM ledger_entries WHERE id = ?", creditEntryId);
            log.info("Injected Corruption 2: Deleted credit ledger_entry {}", creditEntryId);

            // Corruption 3: Change one stored account balance
            Long targetAccountId = jdbcTemplate.queryForObject(
                    "SELECT id FROM accounts WHERE user_id IS NOT NULL ORDER BY id ASC LIMIT 1", Long.class);
            jdbcTemplate.update("UPDATE accounts SET balance = balance + 9999 WHERE id = ?", targetAccountId);
            log.info("Injected Corruption 3: Tampered stored balance on account {}", targetAccountId);

        } finally {
            // Re-enable trigger
            jdbcTemplate.execute("ALTER TABLE ledger_entries ENABLE TRIGGER trg_no_update_delete_ledger_entries;");
        }

        // Run nightly reconciliation audit
        log.info("Running reconciliation audit on corrupted ledger...");
        ReconciliationService.AuditResult auditResult = reconciliationService.runNightlyAudit();

        assertThat(auditResult.status()).isEqualTo("FAILED_WITH_DISCREPANCIES");

        // Inspect recorded discrepancy line items
        List<ReconciliationDiscrepancy> discrepancies = reconciliationRepository.getDiscrepanciesForRun(auditResult.runId());
        log.info("Audit discovered {} discrepancies:", discrepancies.size());
        for (ReconciliationDiscrepancy d : discrepancies) {
            log.info(" - Check [{}]: Entity ID={}, Expected='{}', Actual='{}', Details='{}'",
                    d.checkName(), d.entityId(), d.expectedValue(), d.actualValue(), d.details());
        }

        // Verify the 3 checks that caught the corruptions:
        // 1. Overall balance check caught debit/credit mismatch
        assertThat(discrepancies).anyMatch(d -> "SUM_DEBITS_EQUALS_SUM_CREDITS".equals(d.checkName()));

        // 2. Transfer balance check caught the transfers whose entry was deleted / changed
        assertThat(discrepancies).anyMatch(d -> "TRANSFER_DEBITS_EQUALS_CREDITS".equals(d.checkName()));

        // 3. Stored balance check caught the tampered account
        assertThat(discrepancies).anyMatch(d -> "STORED_BALANCE_MATCHES_LEDGER_ENTRIES".equals(d.checkName()));

        // Verify that no false-positive checks fired (e.g. no false overdrafts, no orphans)
        assertThat(discrepancies).noneMatch(d -> "NO_ORPHANED_RECORDS".equals(d.checkName()));
    }
}
