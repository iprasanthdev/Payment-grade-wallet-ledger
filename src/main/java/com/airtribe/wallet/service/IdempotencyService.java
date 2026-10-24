package com.airtribe.wallet.service;

import com.airtribe.wallet.exception.DuplicateIdempotencyKeyException;
import com.airtribe.wallet.exception.InvalidIdempotencyKeyException;
import com.airtribe.wallet.model.IdempotencyKeyRecord;
import com.airtribe.wallet.model.IdempotencyStatus;
import com.airtribe.wallet.repository.IdempotencyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRepository idempotencyRepository, ObjectMapper objectMapper) {
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
    }

    public void validateKeyFormat(String key) {
        if (key == null || key.isBlank()) {
            throw new InvalidIdempotencyKeyException("Idempotency-Key header is required on all POST requests");
        }
        try {
            UUID.fromString(key.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidIdempotencyKeyException("Idempotency-Key must be a valid UUID. Provided: " + key);
        }
    }

    public String computeRequestHash(Object requestBody) {
        try {
            String json = objectMapper.writeValueAsString(requestBody);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute SHA-256 request hash", e);
        }
    }

    public sealed interface IdempotencyCheckResult permits IdempotencyCheckResult.NewRequest, IdempotencyCheckResult.CachedResponse, IdempotencyCheckResult.Conflict {
        record NewRequest() implements IdempotencyCheckResult {}
        record CachedResponse(int statusCode, String responseBody) implements IdempotencyCheckResult {}
        record Conflict(String message) implements IdempotencyCheckResult {}
    }

    /**
     * Checks idempotency key state and attempts atomic claim.
     */
    public IdempotencyCheckResult checkAndAcquire(Long userId, String key, String requestHash) {
        boolean inserted = idempotencyRepository.tryInsertKey(userId, key, requestHash);
        if (inserted) {
            // Successfully claimed new key
            return new IdempotencyCheckResult.NewRequest();
        }

        // Key already exists: check its hash and state
        Optional<IdempotencyKeyRecord> recordOpt = idempotencyRepository.findByUserAndKey(userId, key);
        if (recordOpt.isEmpty()) {
            // Race condition: retry insertion once
            if (idempotencyRepository.tryInsertKey(userId, key, requestHash)) {
                return new IdempotencyCheckResult.NewRequest();
            }
            recordOpt = idempotencyRepository.findByUserAndKey(userId, key);
        }

        if (recordOpt.isPresent()) {
            IdempotencyKeyRecord record = recordOpt.get();
            // Check hash mismatch
            if (!record.requestHash().equals(requestHash)) {
                log.warn("Idempotency key {} reused with different payload hash (stored={}, provided={})",
                        key, record.requestHash(), requestHash);
                throw new DuplicateIdempotencyKeyException("Idempotency key '" + key + "' was previously used with a different request payload.");
            }

            if (record.status() == IdempotencyStatus.COMPLETED && record.responseBody() != null) {
                log.info("Returning cached response for idempotency key {} (status={})", key, record.responseCode());
                return new IdempotencyCheckResult.CachedResponse(record.responseCode(), record.responseBody());
            }

            if (record.status() == IdempotencyStatus.PROCESSING) {
                return new IdempotencyCheckResult.Conflict("Payment request with this idempotency key is currently processing. Please retry shortly.");
            }
        }

        return new IdempotencyCheckResult.Conflict("Concurrent or conflicting idempotency request.");
    }

    public void saveSuccess(Long userId, String key, int statusCode, Object responseBody) {
        try {
            String json = objectMapper.writeValueAsString(responseBody);
            idempotencyRepository.updateCompleted(userId, key, statusCode, json);
        } catch (Exception e) {
            log.error("Failed to serialize response for idempotency caching", e);
        }
    }

    public void saveFailure(Long userId, String key, int statusCode, Object errorResponse) {
        try {
            String json = objectMapper.writeValueAsString(errorResponse);
            idempotencyRepository.updateFailed(userId, key, statusCode, json);
        } catch (Exception e) {
            log.error("Failed to record idempotency failure", e);
        }
    }
}
