package com.airtribe.wallet.service;

import com.airtribe.wallet.model.RiskRule;
import com.airtribe.wallet.repository.RiskRepository;
import com.airtribe.wallet.repository.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class FraudDetectionService {

    private static final Logger log = LoggerFactory.getLogger(FraudDetectionService.class);
    private final RiskRepository riskRepository;
    private final TransferRepository transferRepository;

    public FraudDetectionService(RiskRepository riskRepository, TransferRepository transferRepository) {
        this.riskRepository = riskRepository;
        this.transferRepository = transferRepository;
    }

    public record RiskAssessment(
            boolean shouldHold,
            List<TriggeredRule> triggeredRules
    ) {
        public static RiskAssessment pass() {
            return new RiskAssessment(false, List.of());
        }
    }

    public record TriggeredRule(
            RiskRule rule,
            String reason
    ) {}

    /**
     * Evaluates active fraud rules inside the transfer transaction.
     * Thresholds are loaded dynamically from risk_rules table.
     */
    public RiskAssessment assessTransfer(Long sourceAccountId, long amountPaise) {
        List<RiskRule> activeRules = riskRepository.findActiveRules();
        boolean shouldHold = false;
        List<TriggeredRule> triggered = new ArrayList<>();

        for (RiskRule rule : activeRules) {
            switch (rule.ruleType()) {
                case "SINGLE_TRANSACTION_LIMIT" -> {
                    // Triggers if single transfer exceeds threshold (e.g. ₹10,000 = 1,000,000 paise)
                    if (amountPaise >= rule.thresholdValue()) {
                        String reason = String.format("Transfer amount ₹%.2f exceeds limit of ₹%.2f (Rule: %s)",
                                amountPaise / 100.0, rule.thresholdValue() / 100.0, rule.ruleName());
                        log.warn("Risk rule triggered: {}", reason);
                        triggered.add(new TriggeredRule(rule, reason));
                        if ("HOLD".equalsIgnoreCase(rule.action())) {
                            shouldHold = true;
                        }
                    }
                }
                case "VELOCITY_1_MIN" -> {
                    // Triggers if source account made > threshold transfers in past 60 seconds
                    int count = transferRepository.countTransfersInLastSeconds(sourceAccountId, 60);
                    if (count >= rule.thresholdValue()) {
                        String reason = String.format("Rapid velocity detected: %d transfers from account %d in past 60s exceeds threshold %d (Rule: %s)",
                                count, sourceAccountId, rule.thresholdValue(), rule.ruleName());
                        log.warn("Risk rule triggered: {}", reason);
                        triggered.add(new TriggeredRule(rule, reason));
                        if ("HOLD".equalsIgnoreCase(rule.action())) {
                            shouldHold = true;
                        }
                    }
                }
                default -> log.debug("Unknown rule type: {}", rule.ruleType());
            }
        }

        return new RiskAssessment(shouldHold, triggered);
    }
}
