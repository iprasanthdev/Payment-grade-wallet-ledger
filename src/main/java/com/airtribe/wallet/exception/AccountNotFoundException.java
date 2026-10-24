package com.airtribe.wallet.exception;

import org.springframework.http.HttpStatus;

public class AccountNotFoundException extends WalletException {
    public AccountNotFoundException(String message) {
        super("ACCOUNT_NOT_FOUND", message, HttpStatus.NOT_FOUND);
    }
}
