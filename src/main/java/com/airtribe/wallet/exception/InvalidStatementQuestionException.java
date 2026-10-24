package com.airtribe.wallet.exception;

import org.springframework.http.HttpStatus;

public class InvalidStatementQuestionException extends WalletException {
    public InvalidStatementQuestionException(String message) {
        super("UNSUPPORTED_QUESTION", message, HttpStatus.BAD_REQUEST);
    }
}
