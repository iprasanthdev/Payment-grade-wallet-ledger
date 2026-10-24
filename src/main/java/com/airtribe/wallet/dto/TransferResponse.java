package com.airtribe.wallet.dto;

import com.airtribe.wallet.model.TransferStatus;
import com.airtribe.wallet.model.TransferType;
import java.time.Instant;

public record TransferResponse(
        Long id,
        TransferType transferType,
        TransferStatus status,
        long amountPaise,
        double amountRupees,
        Long sourceAccountId,
        Long destinationAccountId,
        String note,
        Instant createdAt
) {
    public static TransferResponse from(
            Long id,
            TransferType transferType,
            TransferStatus status,
            long amountPaise,
            Long sourceAccountId,
            Long destinationAccountId,
            String note,
            Instant createdAt
    ) {
        return new TransferResponse(
                id,
                transferType,
                status,
                amountPaise,
                amountPaise / 100.0,
                sourceAccountId,
                destinationAccountId,
                note,
                createdAt
        );
    }
}
