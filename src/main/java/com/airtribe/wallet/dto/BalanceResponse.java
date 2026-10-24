package com.airtribe.wallet.dto;

public record BalanceResponse(
        Long walletId,
        long balancePaise,
        double balanceRupees,
        String currency
) {
    public static BalanceResponse of(Long walletId, long balancePaise, String currency) {
        return new BalanceResponse(walletId, balancePaise, balancePaise / 100.0, currency);
    }
}
