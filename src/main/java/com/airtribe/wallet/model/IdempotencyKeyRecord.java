package com.airtribe.wallet.model;

import java.time.Instant;

public record IdempotencyKeyRecord(
        Long id,
        Long userId,
        String key,
        String requestHash,
        IdempotencyStatus status,
        Integer responseCode,
        String responseBody,
        Instant createdAt,
        Instant updatedAt
) {}
