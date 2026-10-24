package com.airtribe.wallet.model;

import java.time.Instant;

public record Account(
        Long id,
        Long userId,
        AccountType accountType,
        String currency,
        long balance, // in paise
        String status,
        Instant createdAt,
        Instant updatedAt
) {
    public boolean isUserAccount() {
        return userId != null;
    }
}
