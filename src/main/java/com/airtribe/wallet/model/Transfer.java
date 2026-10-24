package com.airtribe.wallet.model;

import java.time.Instant;

public record Transfer(
        Long id,
        TransferType transferType,
        TransferStatus status,
        long amount, // paise
        Long sourceAccountId,
        Long destinationAccountId,
        Long relatedTransferId,
        String note,
        Instant createdAt,
        Instant updatedAt
) {}
