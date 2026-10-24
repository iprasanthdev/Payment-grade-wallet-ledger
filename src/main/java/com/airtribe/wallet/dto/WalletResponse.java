package com.airtribe.wallet.dto;

import com.airtribe.wallet.model.AccountType;
import java.time.Instant;

public record WalletResponse(
        Long id,
        Long userId,
        AccountType accountType,
        String currency,
        long balancePaise,
        double balanceRupees,
        String status,
        Instant createdAt
) {
    public static WalletResponse from(Long id, Long userId, AccountType accountType, String currency, long balancePaise, String status, Instant createdAt) {
        return new WalletResponse(id, userId, accountType, currency, balancePaise, balancePaise / 100.0, status, createdAt);
    }
}
