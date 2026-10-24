package com.airtribe.wallet.model;

public enum AccountType {
    USER_MAIN,
    USER_SAVINGS,
    SYSTEM_CASH_IN,
    SYSTEM_CASH_OUT,
    SYSTEM_FEE_REVENUE;

    public boolean isSystemAccount() {
        return this == SYSTEM_CASH_IN || this == SYSTEM_CASH_OUT || this == SYSTEM_FEE_REVENUE;
    }
}
