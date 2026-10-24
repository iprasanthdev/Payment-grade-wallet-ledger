package com.airtribe.wallet.worker;

import com.airtribe.wallet.model.RiskFlag;
import com.airtribe.wallet.model.Transfer;
import com.airtribe.wallet.repository.RiskRepository;
import com.airtribe.wallet.repository.TransferRepository;
import com.airtribe.wallet.service.LlmService;
import com.airtribe.wallet.service.ReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class NightlyJobWorker {

    private static final Logger log = LoggerFactory.getLogger(NightlyJobWorker.class);

    private final ReconciliationService reconciliationService;
    private final RiskRepository riskRepository;
    private final TransferRepository transferRepository;
    private final LlmService llmService;

    public NightlyJobWorker(
            ReconciliationService reconciliationService,
            RiskRepository riskRepository,
            TransferRepository transferRepository,
            LlmService llmService
    ) {
        this.reconciliationService = reconciliationService;
        this.riskRepository = riskRepository;
        this.transferRepository = transferRepository;
        this.llmService = llmService;
    }

    /**
     * Nightly scheduled audit & risk explanation runner (2:00 AM daily).
     */
    @Scheduled(cron = "${wallet.jobs.nightly-cron:0 0 2 * * *}")
    public void executeNightlyTasks() {
        log.info("Executing scheduled nightly ledger tasks...");
        try {
            // Task 1: Audit books
            reconciliationService.runNightlyAudit();
        } catch (Exception e) {
            log.error("Nightly reconciliation audit failed", e);
        }

        try {
            // Task 2: Explain flagged transfers with LLM
            explainPendingRiskFlags();
        } catch (Exception e) {
            log.error("Nightly risk flag explanation failed", e);
        }
    }

    public int explainPendingRiskFlags() {
        List<RiskFlag> pendingFlags = riskRepository.findUnexplainedFlags();
        log.info("Found {} pending risk flags to explain with LLM", pendingFlags.size());

        int processed = 0;
        for (RiskFlag flag : pendingFlags) {
            try {
                // Fetch wallet's last 10 transfers
                Transfer transfer = transferRepository.findById(flag.transferId()).orElse(null);
                List<Transfer> recent = transfer != null 
                        ? transferRepository.findRecentTransfersForAccount(transfer.sourceAccountId(), 10) 
                        : List.of();

                LlmService.LlmExplanationResult result = llmService.explainRiskFlag(flag, recent);
                riskRepository.updateLlmExplanation(flag.id(), result.explanation(), result.label());
                processed++;
            } catch (Exception e) {
                log.error("Failed to explain flag {}", flag.id(), e);
            }
        }
        return processed;
    }
}
