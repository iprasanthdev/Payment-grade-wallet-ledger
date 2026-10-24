package com.airtribe.wallet.exception;

import org.springframework.http.HttpStatus;

public class JobLockAcquisitionException extends WalletException {
    public JobLockAcquisitionException(String message) {
        super("JOB_LOCK_BUSY", message, HttpStatus.CONFLICT);
    }
}
