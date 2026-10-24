package com.airtribe.wallet.dto;

import java.time.Instant;

public record UserResponse(
        Long id,
        String name,
        String email,
        String phone,
        String kycStatus,
        Instant createdAt
) {}
