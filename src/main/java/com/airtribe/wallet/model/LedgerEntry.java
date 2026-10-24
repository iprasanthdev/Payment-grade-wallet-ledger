package com.airtribe.wallet.model;

import java.time.Instant;

public record LedgerEntry(
        Long id,
        Long transferId,
        Long accountId,
        LedgerDirection direction,
        long amount, // paise
        Instant createdAt
) {}
