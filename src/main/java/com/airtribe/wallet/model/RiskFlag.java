package com.airtribe.wallet.model;

import java.time.Instant;

public record RiskFlag(
        Long id,
        Long transferId,
        Long ruleId,
        String reason,
        String llmExplanation,
        String llmLabel, // likely_ok, review, likely_fraud
        Instant createdAt
) {}
