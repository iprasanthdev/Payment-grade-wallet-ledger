package com.airtribe.wallet.dto;

import com.airtribe.wallet.model.AccountType;
import jakarta.validation.constraints.NotNull;

public record CreateWalletRequest(
        @NotNull(message = "User ID is required") Long userId,
        AccountType accountType // default to USER_MAIN if null
) {}
