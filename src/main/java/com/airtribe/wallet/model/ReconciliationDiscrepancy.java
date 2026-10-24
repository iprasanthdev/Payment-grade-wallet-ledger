package com.airtribe.wallet.model;

import java.time.Instant;

public record ReconciliationDiscrepancy(
        Long id,
        Long runId,
        String checkName,
        Long entityId,
        String expectedValue,
        String actualValue,
        String details,
        Instant detectedAt
) {}
