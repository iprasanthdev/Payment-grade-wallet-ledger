package com.airtribe.wallet.service;

import com.airtribe.wallet.dto.StatementAskResponse;
import com.airtribe.wallet.exception.UserNotFoundException;
import com.airtribe.wallet.model.Account;
import com.airtribe.wallet.repository.AccountRepository;
import com.airtribe.wallet.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;

@Service
public class StatementQaService {

    private static final Logger log = LoggerFactory.getLogger(StatementQaService.class);

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final LlmService llmService;
    private final JdbcTemplate jdbcTemplate;

    public StatementQaService(
            UserRepository userRepository,
            AccountRepository accountRepository,
            LlmService llmService,
            JdbcTemplate jdbcTemplate
    ) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.llmService = llmService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Executes natural language Statement Q&A safely:
     * 1. Ask LLM to extract JSON query parameters.
     * 2. Validate JSON in Java code.
     * 3. Run strictly parameterized SQL query limited ONLY to caller's account IDs.
     *    LLM NEVER WRITES SQL!
     * 4. Return results and originating transfer IDs.
     */
    public StatementAskResponse answerQuestion(Long userId, String question) {
        userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User " + userId + " not found"));

        List<Long> accountIds = accountRepository.findByUserId(userId).stream()
                .map(Account::id)
                .toList();

        if (accountIds.isEmpty()) {
            return StatementAskResponse.of(question, 0, "No wallets found for user", 0L, 0L, List.of());
        }

        // Step 1 & 2: LLM JSON parse & validation
        LlmService.ParsedStatementQuery query = llmService.parseStatementQuestion(question);

        Timestamp fromTs = Timestamp.valueOf(query.fromDate().atStartOfDay());
        Timestamp toTs = Timestamp.valueOf(query.toDate().atTime(LocalTime.MAX));

        String inPlaceholders = String.join(",", Collections.nCopies(accountIds.size(), "?"));

        switch (query.type()) {
            case 1 -> {
                // Total sent to person
                String counterparty = query.counterparty() != null ? query.counterparty().trim() : "";
                String sql = String.format("""
                    SELECT t.id, t.amount
                    FROM transfers t
                    LEFT JOIN accounts da ON t.destination_account_id = da.id
                    LEFT JOIN users du ON da.user_id = du.id
                    WHERE t.source_account_id IN (%s)
                      AND t.status = 'COMPLETED'
                      AND t.created_at BETWEEN ? AND ?
                      AND (du.name ILIKE ? OR t.note ILIKE ?)
                    ORDER BY t.created_at DESC
                    """, inPlaceholders);

                Object[] params = new Object[accountIds.size() + 4];
                int idx = 0;
                for (Long id : accountIds) params[idx++] = id;
                params[idx++] = fromTs;
                params[idx++] = toTs;
                params[idx++] = "%" + counterparty + "%";
                params[idx] = "%" + counterparty + "%";

                List<Long> transferIds = new java.util.ArrayList<>();
                long totalPaise = 0L;
                List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params);
                for (var row : rows) {
                    transferIds.add(((Number) row.get("id")).longValue());
                    totalPaise += ((Number) row.get("amount")).longValue();
                }

                String summary = String.format("Total sent to %s between %s and %s: ₹%.2f across %d transfer(s)",
                        counterparty, query.fromDate(), query.toDate(), totalPaise / 100.0, transferIds.size());

                return StatementAskResponse.of(question, 1, summary, totalPaise, (long) transferIds.size(), transferIds);
            }
            case 2 -> {
                // Total received in date range
                String sql = String.format("""
                    SELECT t.id, t.amount
                    FROM transfers t
                    WHERE t.destination_account_id IN (%s)
                      AND t.status = 'COMPLETED'
                      AND t.created_at BETWEEN ? AND ?
                    ORDER BY t.created_at DESC
                    """, inPlaceholders);

                Object[] params = new Object[accountIds.size() + 2];
                int idx = 0;
                for (Long id : accountIds) params[idx++] = id;
                params[idx++] = fromTs;
                params[idx] = toTs;

                List<Long> transferIds = new java.util.ArrayList<>();
                long totalPaise = 0L;
                List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params);
                for (var row : rows) {
                    transferIds.add(((Number) row.get("id")).longValue());
                    totalPaise += ((Number) row.get("amount")).longValue();
                }

                String summary = String.format("Total received between %s and %s: ₹%.2f across %d transfer(s)",
                        query.fromDate(), query.toDate(), totalPaise / 100.0, transferIds.size());

                return StatementAskResponse.of(question, 2, summary, totalPaise, (long) transferIds.size(), transferIds);
            }
            case 3 -> {
                // Largest transfer in date range
                String sql = String.format("""
                    SELECT t.id, t.amount
                    FROM transfers t
                    WHERE (t.source_account_id IN (%s) OR t.destination_account_id IN (%s))
                      AND t.status = 'COMPLETED'
                      AND t.created_at BETWEEN ? AND ?
                    ORDER BY t.amount DESC, t.id DESC
                    LIMIT 1
                    """, inPlaceholders, inPlaceholders);

                Object[] params = new Object[accountIds.size() * 2 + 2];
                int idx = 0;
                for (Long id : accountIds) params[idx++] = id;
                for (Long id : accountIds) params[idx++] = id;
                params[idx++] = fromTs;
                params[idx] = toTs;

                List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params);
                if (rows.isEmpty()) {
                    return StatementAskResponse.of(question, 3, "No transfers found in range", 0L, 0L, List.of());
                }
                Long maxId = ((Number) rows.get(0).get("id")).longValue();
                Long maxAmount = ((Number) rows.get(0).get("amount")).longValue();
                String summary = String.format("Largest transfer between %s and %s: ₹%.2f (Transfer #%d)",
                        query.fromDate(), query.toDate(), maxAmount / 100.0, maxId);

                return StatementAskResponse.of(question, 3, summary, maxAmount, 1L, List.of(maxId));
            }
            case 4 -> {
                // Number of transfers in date range
                String sql = String.format("""
                    SELECT t.id
                    FROM transfers t
                    WHERE (t.source_account_id IN (%s) OR t.destination_account_id IN (%s))
                      AND t.status = 'COMPLETED'
                      AND t.created_at BETWEEN ? AND ?
                    ORDER BY t.created_at DESC
                    """, inPlaceholders, inPlaceholders);

                Object[] params = new Object[accountIds.size() * 2 + 2];
                int idx = 0;
                for (Long id : accountIds) params[idx++] = id;
                for (Long id : accountIds) params[idx++] = id;
                params[idx++] = fromTs;
                params[idx] = toTs;

                List<Long> transferIds = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getLong("id"), params);
                String summary = String.format("Total number of transfers between %s and %s: %d transfer(s)",
                        query.fromDate(), query.toDate(), transferIds.size());

                return StatementAskResponse.of(question, 4, summary, null, (long) transferIds.size(), transferIds);
            }
            default -> throw new IllegalStateException("Unexpected question type: " + query.type());
        }
    }
}
