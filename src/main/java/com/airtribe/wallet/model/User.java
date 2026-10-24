package com.airtribe.wallet.model;

import java.time.Instant;

public record User(
        Long id,
        String name,
        String email,
        String phone,
        String kycStatus,
        Instant createdAt,
        Instant updatedAt
) {}
