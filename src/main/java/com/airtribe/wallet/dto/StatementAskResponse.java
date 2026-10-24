package com.airtribe.wallet.dto;

import java.util.List;

public record StatementAskResponse(
        String question,
        int questionType, // 1: Total sent, 2: Total received, 3: Largest transfer, 4: Number of transfers
        String answerSummary,
        Long resultAmountPaise,
        Double resultAmountRupees,
        Long resultCount,
        List<Long> transferIds
) {
    public static StatementAskResponse of(
            String question,
            int questionType,
            String answerSummary,
            Long resultAmountPaise,
            Long resultCount,
            List<Long> transferIds
    ) {
        Double rupees = resultAmountPaise != null ? resultAmountPaise / 100.0 : null;
        return new StatementAskResponse(question, questionType, answerSummary, resultAmountPaise, rupees, resultCount, transferIds);
    }
}
