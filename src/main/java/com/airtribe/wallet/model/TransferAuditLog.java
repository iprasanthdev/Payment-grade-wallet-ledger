package com.airtribe.wallet.model;

import java.time.Instant;

public record TransferAuditLog(
        Long id,
        Long transferId,
        String fromStatus,
        String toStatus,
        String changedBy,
        String reason,
        Instant createdAt
) {}
