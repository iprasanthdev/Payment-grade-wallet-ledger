package com.airtribe.wallet.exception;

import org.springframework.http.HttpStatus;

public class DuplicateIdempotencyKeyException extends WalletException {
    public DuplicateIdempotencyKeyException(String message) {
        super("IDEMPOTENCY_CONFLICT", message, HttpStatus.UNPROCESSABLE_ENTITY); // 422 per brief
    }
}
