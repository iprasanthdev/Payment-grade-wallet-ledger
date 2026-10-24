package com.airtribe.wallet.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MockLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(MockLlmClient.class);

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        log.info("MockLlmClient invoked with userPrompt: '{}'", userPrompt);

        // Risk explanation prompt
        if (systemPrompt != null && systemPrompt.contains("explain_risk")) {
            return """
                {
                  "explanation": "The transaction exceeded typical baseline thresholds for this wallet. Multiple sequential transfers were initiated in quick succession.",
                  "label": "review"
                }
                """;
        }

        // Statement Q&A Prompt
        String lower = userPrompt.toLowerCase();

        // 1. Total sent to a person in date range (type: 1)
        // e.g. "How much did I send to Rahul last month?" or "Total sent to Bob between 2026-08-01 and 2026-08-31"
        if (lower.contains("send to") || lower.contains("sent to") || lower.contains("paid to")) {
            String counterparty = extractCounterparty(userPrompt);
            DateRange range = extractDateRange(lower);
            return String.format("""
                {
                  "type": 1,
                  "counterparty": "%s",
                  "from": "%s",
                  "to": "%s"
                }
                """, counterparty, range.from, range.to);
        }

        // 2. Total received in date range (type: 2)
        // e.g. "How much did I receive last month?" or "Total received between 2026-01-01 and 2026-01-31"
        if (lower.contains("receive") || lower.contains("received") || lower.contains("got from") || lower.contains("incoming")) {
            DateRange range = extractDateRange(lower);
            return String.format("""
                {
                  "type": 2,
                  "from": "%s",
                  "to": "%s"
                }
                """, range.from, range.to);
        }

        // 3. Largest transfer in date range (type: 3)
        // e.g. "What was my largest transfer this year?" or "Maximum transfer in August 2026"
        if (lower.contains("largest") || lower.contains("maximum") || lower.contains("highest") || lower.contains("biggest")) {
            DateRange range = extractDateRange(lower);
            return String.format("""
                {
                  "type": 3,
                  "from": "%s",
                  "to": "%s"
                }
                """, range.from, range.to);
        }

        // 4. Number of transfers in date range (type: 4)
        // e.g. "How many transfers did I make in August?" or "Number of transfers between 2026-01-01 and 2026-08-31"
        if (lower.contains("number of transfers") || lower.contains("how many transfers") || lower.contains("transaction count")) {
            DateRange range = extractDateRange(lower);
            return String.format("""
                {
                  "type": 4,
                  "from": "%s",
                  "to": "%s"
                }
                """, range.from, range.to);
        }

        // Unsupported question
        return "{\"type\": -1, \"message\": \"Unsupported question category\"}";
    }

    private String extractCounterparty(String prompt) {
        Pattern pattern = Pattern.compile("(?:to|sent to|paid to)\\s+([A-Za-z]+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(prompt);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "Unknown";
    }

    private record DateRange(String from, String to) {}

    private DateRange extractDateRange(String lower) {
        // Look for explicit dates YYYY-MM-DD
        Pattern p = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
        Matcher m = p.matcher(lower);
        String d1 = null, d2 = null;
        if (m.find()) d1 = m.group(1);
        if (m.find()) d2 = m.group(1);

        if (d1 != null && d2 != null) {
            return new DateRange(d1, d2);
        }
        if (d1 != null) {
            return new DateRange(d1, LocalDate.now().toString());
        }

        // Relative ranges
        LocalDate now = LocalDate.now();
        if (lower.contains("last month")) {
            LocalDate firstDayLastMonth = now.minusMonths(1).withDayOfMonth(1);
            LocalDate lastDayLastMonth = now.minusMonths(1).withDayOfMonth(now.minusMonths(1).lengthOfMonth());
            return new DateRange(firstDayLastMonth.toString(), lastDayLastMonth.toString());
        }
        if (lower.contains("this month")) {
            return new DateRange(now.withDayOfMonth(1).toString(), now.toString());
        }
        if (lower.contains("this year")) {
            return new DateRange(LocalDate.of(now.getYear(), 1, 1).toString(), now.toString());
        }

        // Default to past 1 year
        return new DateRange(now.minusYears(1).toString(), now.plusDays(1).toString());
    }
}
