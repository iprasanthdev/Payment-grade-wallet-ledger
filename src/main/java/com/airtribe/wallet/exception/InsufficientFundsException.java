package com.airtribe.wallet.exception;

import org.springframework.http.HttpStatus;

public class InsufficientFundsException extends WalletException {
    public InsufficientFundsException(String message) {
        super("INSUFFICIENT_FUNDS", message, HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
