package com.airtribe.wallet.repository;

import com.airtribe.wallet.model.RiskFlag;
import com.airtribe.wallet.model.RiskRule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class RiskRepository {

    private final JdbcTemplate jdbcTemplate;

    public RiskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<RiskRule> ruleRowMapper = (rs, rowNum) -> new RiskRule(
            rs.getLong("id"),
            rs.getString("rule_name"),
            rs.getString("rule_type"),
            rs.getLong("threshold_value"),
            rs.getString("action"),
            rs.getString("description"),
            rs.getBoolean("is_active"),
            rs.getTimestamp("created_at").toInstant()
    );

    private final RowMapper<RiskFlag> flagRowMapper = (rs, rowNum) -> new RiskFlag(
            rs.getLong("id"),
            rs.getLong("transfer_id"),
            rs.getLong("rule_id"),
            rs.getString("reason"),
            rs.getString("llm_explanation"),
            rs.getString("llm_label"),
            rs.getTimestamp("created_at").toInstant()
    );

    public List<RiskRule> findActiveRules() {
        String sql = "SELECT id, rule_name, rule_type, threshold_value, action, description, is_active, created_at FROM risk_rules WHERE is_active = TRUE";
        return jdbcTemplate.query(sql, ruleRowMapper);
    }

    public RiskFlag createFlag(Long transferId, Long ruleId, String reason) {
        String sql = """
            INSERT INTO risk_flags (transfer_id, rule_id, reason)
            VALUES (?, ?, ?)
            RETURNING id, transfer_id, rule_id, reason, llm_explanation, llm_label, created_at
            """;
        return jdbcTemplate.queryForObject(sql, flagRowMapper, transferId, ruleId, reason);
    }

    public List<RiskFlag> findUnexplainedFlags() {
        String sql = "SELECT id, transfer_id, rule_id, reason, llm_explanation, llm_label, created_at FROM risk_flags WHERE llm_explanation IS NULL ORDER BY id ASC";
        return jdbcTemplate.query(sql, flagRowMapper);
    }

    public void updateLlmExplanation(Long flagId, String explanation, String label) {
        String sql = "UPDATE risk_flags SET llm_explanation = ?, llm_label = ? WHERE id = ?";
        jdbcTemplate.update(sql, explanation, label, flagId);
    }

    public List<RiskFlag> findFlagsByTransferId(Long transferId) {
        String sql = "SELECT id, transfer_id, rule_id, reason, llm_explanation, llm_label, created_at FROM risk_flags WHERE transfer_id = ?";
        return jdbcTemplate.query(sql, flagRowMapper, transferId);
    }
}
