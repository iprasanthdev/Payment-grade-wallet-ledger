package com.airtribe.wallet.repository;

import com.airtribe.wallet.model.Account;
import com.airtribe.wallet.model.AccountType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Repository
public class AccountRepository {

    private final JdbcTemplate jdbcTemplate;

    public AccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<Account> rowMapper = (rs, rowNum) -> new Account(
            rs.getLong("id"),
            rs.getObject("user_id") != null ? rs.getLong("user_id") : null,
            AccountType.valueOf(rs.getString("account_type")),
            rs.getString("currency"),
            rs.getLong("balance"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    public Account create(Long userId, AccountType accountType, String currency, long initialBalance) {
        String sql = """
            INSERT INTO accounts (user_id, account_type, currency, balance, status)
            VALUES (?, ?, ?, ?, 'ACTIVE')
            RETURNING id, user_id, account_type, currency, balance, status, created_at, updated_at
            """;
        return jdbcTemplate.queryForObject(
                sql,
                rowMapper,
                userId,
                accountType.name(),
                currency != null ? currency : "INR",
                initialBalance
        );
    }

    public Optional<Account> findById(Long id) {
        String sql = "SELECT id, user_id, account_type, currency, balance, status, created_at, updated_at FROM accounts WHERE id = ?";
        return jdbcTemplate.query(sql, rowMapper, id).stream().findFirst();
    }

    public List<Account> findByUserId(Long userId) {
        String sql = "SELECT id, user_id, account_type, currency, balance, status, created_at, updated_at FROM accounts WHERE user_id = ? ORDER BY id ASC";
        return jdbcTemplate.query(sql, rowMapper, userId);
    }

    /**
     * CRITICAL MONEY PATH METHOD: Row-level lock acquisition.
     * Takes exclusive write lock on account rows in strict ASCENDING account_id order.
     * Guarantees deadlock freedom under heavy concurrent stampedes.
     */
    public List<Account> lockAccountsForUpdate(List<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> sortedIds = accountIds.stream().distinct().sorted().toList();
        String inPlaceholders = String.join(",", Collections.nCopies(sortedIds.size(), "?"));
        String sql = "SELECT id, user_id, account_type, currency, balance, status, created_at, updated_at " +
                     "FROM accounts WHERE id IN (" + inPlaceholders + ") ORDER BY id ASC FOR UPDATE";
        return jdbcTemplate.query(sql, rowMapper, sortedIds.toArray());
    }

    /**
     * Atomically updates stored balance.
     */
    public void updateBalance(Long accountId, long balanceDelta) {
        String sql = "UPDATE accounts SET balance = balance + ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        jdbcTemplate.update(sql, balanceDelta, accountId);
    }

    public long getStoredBalance(Long accountId) {
        String sql = "SELECT balance FROM accounts WHERE id = ?";
        return jdbcTemplate.queryForObject(sql, Long.class, accountId);
    }
}
