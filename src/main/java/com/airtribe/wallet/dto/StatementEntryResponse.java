package com.airtribe.wallet.dto;

import com.airtribe.wallet.model.LedgerDirection;
import java.time.Instant;

public record StatementEntryResponse(
        Long entryId,
        Long transferId,
        Long accountId,
        LedgerDirection direction,
        long amountPaise,
        double amountRupees,
        Instant createdAt
) {
    public static StatementEntryResponse from(
            Long entryId,
            Long transferId,
            Long accountId,
            LedgerDirection direction,
            long amountPaise,
            Instant createdAt
    ) {
        return new StatementEntryResponse(
                entryId,
                transferId,
                accountId,
                direction,
                amountPaise,
                amountPaise / 100.0,
                createdAt
        );
    }
}
