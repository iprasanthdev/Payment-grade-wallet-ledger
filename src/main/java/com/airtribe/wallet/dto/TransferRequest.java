package com.airtribe.wallet.dto;

import com.airtribe.wallet.model.TransferType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record TransferRequest(
        @NotNull(message = "Source account ID is required") Long sourceAccountId,
        @NotNull(message = "Destination account ID is required") Long destinationAccountId,
        @Min(value = 1, message = "Amount must be at least 1 paise") long amountPaise,
        TransferType transferType,
        String note
) {}
