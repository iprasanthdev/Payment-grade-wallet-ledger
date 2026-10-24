package com.airtribe.wallet.repository;

import com.airtribe.wallet.model.IdempotencyKeyRecord;
import com.airtribe.wallet.model.IdempotencyStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class IdempotencyRepository {

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<IdempotencyKeyRecord> rowMapper = (rs, rowNum) -> new IdempotencyKeyRecord(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getString("key"),
            rs.getString("request_hash"),
            IdempotencyStatus.valueOf(rs.getString("status")),
            rs.getObject("response_code") != null ? rs.getInt("response_code") : null,
            rs.getString("response_body"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    /**
     * Atomically tries to claim an idempotency key with status PROCESSING.
     * Returns true if insert succeeds; returns false if unique constraint (user_id, key) is violated.
     */
    public boolean tryInsertKey(Long userId, String key, String requestHash) {
        String sql = """
            INSERT INTO idempotency_keys (user_id, key, request_hash, status)
            VALUES (?, ?, ?, 'PROCESSING')
            ON CONFLICT (user_id, key) DO NOTHING
            """;
        int rows = jdbcTemplate.update(sql, userId, key, requestHash);
        return rows > 0;
    }

    public Optional<IdempotencyKeyRecord> findByUserAndKey(Long userId, String key) {
        String sql = "SELECT id, user_id, key, request_hash, status, response_code, response_body, created_at, updated_at " +
                     "FROM idempotency_keys WHERE user_id = ? AND key = ?";
        return jdbcTemplate.query(sql, rowMapper, userId, key).stream().findFirst();
    }

    public void updateCompleted(Long userId, String key, int statusCode, String responseBody) {
        String sql = """
            UPDATE idempotency_keys 
            SET status = 'COMPLETED', response_code = ?, response_body = ?, updated_at = CURRENT_TIMESTAMP
            WHERE user_id = ? AND key = ?
            """;
        jdbcTemplate.update(sql, statusCode, responseBody, userId, key);
    }

    public void updateFailed(Long userId, String key, int statusCode, String responseBody) {
        String sql = """
            UPDATE idempotency_keys 
            SET status = 'FAILED', response_code = ?, response_body = ?, updated_at = CURRENT_TIMESTAMP
            WHERE user_id = ? AND key = ?
            """;
        jdbcTemplate.update(sql, statusCode, responseBody, userId, key);
    }

    public int deleteOlderThanHours(int hours) {
        String sql = "DELETE FROM idempotency_keys WHERE created_at < CURRENT_TIMESTAMP - (? || ' hours')::INTERVAL";
        return jdbcTemplate.update(sql, String.valueOf(hours));
    }
}
