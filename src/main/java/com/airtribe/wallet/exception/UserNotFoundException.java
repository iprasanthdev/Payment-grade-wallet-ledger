package com.airtribe.wallet.exception;

import org.springframework.http.HttpStatus;

public class UserNotFoundException extends WalletException {
    public UserNotFoundException(String message) {
        super("USER_NOT_FOUND", message, HttpStatus.NOT_FOUND);
    }
}
