package com.airtribe.wallet.dto;

import java.util.List;

public record StatementPageResponse(
        Long walletId,
        List<StatementEntryResponse> entries,
        int limit,
        int offset,
        Long nextCursor,
        boolean hasMore
) {}
