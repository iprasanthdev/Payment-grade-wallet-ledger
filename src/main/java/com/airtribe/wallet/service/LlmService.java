package com.airtribe.wallet.service;

import com.airtribe.wallet.exception.InvalidStatementQuestionException;
import com.airtribe.wallet.model.RiskFlag;
import com.airtribe.wallet.model.Transfer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public LlmService(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    public record LlmExplanationResult(String explanation, String label) {}

    public LlmExplanationResult explainRiskFlag(RiskFlag flag, List<Transfer> recentTransfers) {
        String systemPrompt = "explain_risk: You are a payment risk analyst. Output only JSON with 'explanation' (max 2 sentences) and 'label' ('likely_ok', 'review', or 'likely_fraud').";
        try {
            String transfersJson = objectMapper.writeValueAsString(recentTransfers);
            String userPrompt = String.format("Rule triggered: %s. Recent wallet transfers: %s", flag.reason(), transfersJson);
            String response = llmClient.complete(systemPrompt, userPrompt);
            JsonNode root = objectMapper.readTree(response);
            String explanation = root.path("explanation").asText("Flagged for automated review.");
            String label = root.path("label").asText("review");
            return new LlmExplanationResult(explanation, label);
        } catch (Exception e) {
            log.error("Failed to parse LLM risk explanation", e);
            return new LlmExplanationResult("Automated risk rule triggered. Review recommended.", "review");
        }
    }

    public record ParsedStatementQuery(
            int type, // 1: Total sent, 2: Total received, 3: Largest transfer, 4: Number of transfers
            String counterparty,
            LocalDate fromDate,
            LocalDate toDate
    ) {}

    /**
     * Translates natural language questions to structured query parameters.
     * Enforces JSON validation. Throws InvalidStatementQuestionException if unsupported.
     */
    public ParsedStatementQuery parseStatementQuestion(String question) {
        String systemPrompt = """
            You are a statement analyzer. Convert user questions to JSON only.
            Supported types:
            1: Total sent to a person in a date range {"type": 1, "counterparty": "name", "from": "YYYY-MM-DD", "to": "YYYY-MM-DD"}
            2: Total received in a date range {"type": 2, "from": "YYYY-MM-DD", "to": "YYYY-MM-DD"}
            3: Largest transfer in a date range {"type": 3, "from": "YYYY-MM-DD", "to": "YYYY-MM-DD"}
            4: Number of transfers in a date range {"type": 4, "from": "YYYY-MM-DD", "to": "YYYY-MM-DD"}
            If not one of these four, return {"type": -1}.
            """;

        String rawJson = llmClient.complete(systemPrompt, question);
        try {
            JsonNode node = objectMapper.readTree(rawJson);
            int type = node.path("type").asInt(-1);

            if (type < 1 || type > 4) {
                throw new InvalidStatementQuestionException(
                        "I can only answer four kinds of questions: total sent to a person in a date range, " +
                        "total received in a date range, largest transfer in a date range, and number of transfers in a date range."
                );
            }

            String counterparty = node.has("counterparty") ? node.path("counterparty").asText() : null;
            String fromStr = node.has("from") ? node.path("from").asText() : LocalDate.now().minusYears(1).toString();
            String toStr = node.has("to") ? node.path("to").asText() : LocalDate.now().toString();

            LocalDate from = LocalDate.parse(fromStr);
            LocalDate to = LocalDate.parse(toStr);

            return new ParsedStatementQuery(type, counterparty, from, to);
        } catch (InvalidStatementQuestionException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Invalid LLM JSON response: {}", rawJson);
            throw new InvalidStatementQuestionException(
                    "I can only answer four kinds of questions: total sent to a person in a date range, " +
                    "total received in a date range, largest transfer in a date range, and number of transfers in a date range."
            );
        }
    }
}
