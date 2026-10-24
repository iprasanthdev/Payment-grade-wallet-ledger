package com.airtribe.wallet.model;

import java.time.Instant;

public record ReconciliationRun(
        Long id,
        Instant startedAt,
        Instant endedAt,
        String status, // RUNNING, SUCCESS, FAILED_WITH_DISCREPANCIES, ERROR
        long totalDebits,
        long totalCredits,
        long transfersChecked,
        long accountsChecked,
        int discrepancyCount
) {}
