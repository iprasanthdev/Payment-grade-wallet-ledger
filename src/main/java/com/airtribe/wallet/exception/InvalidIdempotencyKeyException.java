package com.airtribe.wallet.exception;

import org.springframework.http.HttpStatus;

public class InvalidIdempotencyKeyException extends WalletException {
    public InvalidIdempotencyKeyException(String message) {
        super("INVALID_IDEMPOTENCY_KEY", message, HttpStatus.BAD_REQUEST); // 400 per brief
    }
}
