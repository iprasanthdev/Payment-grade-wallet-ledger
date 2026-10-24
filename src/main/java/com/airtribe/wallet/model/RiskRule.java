package com.airtribe.wallet.model;

import java.time.Instant;

public record RiskRule(
        Long id,
        String ruleName,
        String ruleType,
        long thresholdValue,
        String action, // 'HOLD' or 'FLAG'
        String description,
        boolean isActive,
        Instant createdAt
) {}
