package com.airtribe.wallet.service;

import com.airtribe.wallet.exception.JobLockAcquisitionException;
import com.airtribe.wallet.model.ReconciliationRun;
import com.airtribe.wallet.repository.ReconciliationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final ReconciliationRepository reconciliationRepository;
    private final TransactionTemplate repeatableReadTemplate;

    public ReconciliationService(
            ReconciliationRepository reconciliationRepository,
            PlatformTransactionManager transactionManager
    ) {
        this.reconciliationRepository = reconciliationRepository;

        // Audits run strictly under REPEATABLE READ isolation to provide a point-in-time consistent view
        this.repeatableReadTemplate = new TransactionTemplate(transactionManager);
        this.repeatableReadTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.repeatableReadTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record AuditResult(
            Long runId,
            String status,
            long totalDebits,
            long totalCredits,
            long transfersChecked,
            long accountsChecked,
            int discrepancyCount
    ) {}

    /**
     * Executes the nightly ledger reconciliation audit.
     * Guaranteed single-execution via SELECT ... FOR UPDATE NOWAIT on job_locks table.
     */
    public AuditResult runNightlyAudit() {
        log.info("Starting Nightly Ledger Reconciliation Audit...");

        // Acquire job lock with NOWAIT
        boolean lockAcquired = reconciliationRepository.tryAcquireJobLock("RECONCILIATION_JOB", "NightlyWorker");
        if (!lockAcquired) {
            log.warn("Another reconciliation job is currently in progress. Exiting.");
            throw new JobLockAcquisitionException("Reconciliation job lock is currently held by another worker.");
        }

        Instant startedAt = Instant.now();
        Long runId = reconciliationRepository.createRun(startedAt);

        try {
            return repeatableReadTemplate.execute(status -> {
                int discrepancyCount = 0;

                // 1. Check: Everything balances (sum of all debits equals sum of all credits)
                ReconciliationRepository.TotalBalanceResult totalBal = reconciliationRepository.checkTotalBalance();
                long totalDebits = totalBal.totalDebits();
                long totalCredits = totalBal.totalCredits();
                if (totalDebits != totalCredits) {
                    discrepancyCount++;
                    reconciliationRepository.recordDiscrepancy(
                            runId,
                            "SUM_DEBITS_EQUALS_SUM_CREDITS",
                            null,
                            String.format("total_debits == total_credits (debits=%d, credits=%d)", totalDebits, totalCredits),
                            String.format("Difference: %d paise", Math.abs(totalDebits - totalCredits)),
                            "Total ledger debits do not match total ledger credits across the entire platform"
                    );
                }

                // 2. Check: Every transfer balances (debits == credits per transfer)
                List<ReconciliationRepository.UnbalancedTransfer> unbalanced = reconciliationRepository.checkTransferBalances();
                for (var ut : unbalanced) {
                    discrepancyCount++;
                    reconciliationRepository.recordDiscrepancy(
                            runId,
                            "TRANSFER_DEBITS_EQUALS_CREDITS",
                            ut.transferId(),
                            "debits == credits",
                            String.format("debits=%d, credits=%d", ut.debits(), ut.credits()),
                            "Transfer " + ut.transferId() + " does not balance internally"
                    );
                }

                // 3. Check: Stored balances match computed ledger sums
                List<ReconciliationRepository.AccountBalanceMismatch> mismatches = reconciliationRepository.checkStoredVsComputedBalances();
                for (var abm : mismatches) {
                    discrepancyCount++;
                    reconciliationRepository.recordDiscrepancy(
                            runId,
                            "STORED_BALANCE_MATCHES_LEDGER_ENTRIES",
                            abm.accountId(),
                            String.valueOf(abm.computedBalance()),
                            String.valueOf(abm.storedBalance()),
                            String.format("Account %d stored balance (%d) differs from ledger entries sum (%d)",
                                    abm.accountId(), abm.storedBalance(), abm.computedBalance())
                    );
                }

                // 4. Check: No user wallet has a negative computed balance
                List<ReconciliationRepository.OverdraftAccount> overdrafts = reconciliationRepository.checkNoOverdrafts();
                for (var oa : overdrafts) {
                    discrepancyCount++;
                    reconciliationRepository.recordDiscrepancy(
                            runId,
                            "NO_USER_WALLET_OVERDRAFT",
                            oa.accountId(),
                            ">= 0",
                            String.valueOf(oa.computedBalance()),
                            "User wallet " + oa.accountId() + " computed balance is negative: " + oa.computedBalance()
                    );
                }

                // 5. Check: No orphaned transfers or entries
                List<ReconciliationRepository.OrphanRecord> orphans = reconciliationRepository.checkOrphans();
                for (var or : orphans) {
                    discrepancyCount++;
                    reconciliationRepository.recordDiscrepancy(
                            runId,
                            "NO_ORPHANED_RECORDS",
                            or.id(),
                            "Valid relationship",
                            or.type(),
                            or.details()
                    );
                }

                long transfersCount = reconciliationRepository.countTotalTransfers();
                long accountsCount = reconciliationRepository.countTotalAccounts();

                String finalStatus = discrepancyCount == 0 ? "SUCCESS" : "FAILED_WITH_DISCREPANCIES";
                reconciliationRepository.completeRun(
                        runId,
                        finalStatus,
                        totalDebits,
                        totalCredits,
                        transfersCount,
                        accountsCount,
                        discrepancyCount
                );

                log.info("Reconciliation run {} finished with status {}. Discrepancies found: {}",
                        runId, finalStatus, discrepancyCount);

                return new AuditResult(runId, finalStatus, totalDebits, totalCredits, transfersCount, accountsCount, discrepancyCount);
            });
        } finally {
            reconciliationRepository.releaseJobLock("RECONCILIATION_JOB");
        }
    }

    public List<ReconciliationRun> getRecentRuns(int limit) {
        return reconciliationRepository.getRecentRuns(limit);
    }
}
