package com.airtribe.wallet.repository;

import com.airtribe.wallet.model.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Optional;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<User> rowMapper = (rs, rowNum) -> new User(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("email"),
            rs.getString("phone"),
            rs.getString("kyc_status"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    public User create(String name, String email, String phone, String kycStatus) {
        String sql = "INSERT INTO users (name, email, phone, kyc_status) VALUES (?, ?, ?, ?) RETURNING id, name, email, phone, kyc_status, created_at, updated_at";
        return jdbcTemplate.queryForObject(sql, rowMapper, name, email, phone, kycStatus != null ? kycStatus : "PENDING");
    }

    public Optional<User> findById(Long id) {
        String sql = "SELECT id, name, email, phone, kyc_status, created_at, updated_at FROM users WHERE id = ?";
        return jdbcTemplate.query(sql, rowMapper, id).stream().findFirst();
    }

    public Optional<User> findByEmail(String email) {
        String sql = "SELECT id, name, email, phone, kyc_status, created_at, updated_at FROM users WHERE email = ?";
        return jdbcTemplate.query(sql, rowMapper, email).stream().findFirst();
    }
}
