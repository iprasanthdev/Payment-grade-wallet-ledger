package com.airtribe.wallet.controller;

import com.airtribe.wallet.dto.ErrorResponse;
import com.airtribe.wallet.dto.TransferRequest;
import com.airtribe.wallet.dto.TransferResponse;
import com.airtribe.wallet.exception.AccountNotFoundException;
import com.airtribe.wallet.exception.DuplicateIdempotencyKeyException;
import com.airtribe.wallet.model.Account;
import com.airtribe.wallet.repository.AccountRepository;
import com.airtribe.wallet.service.IdempotencyService;
import com.airtribe.wallet.service.TransferService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;
    private final IdempotencyService idempotencyService;
    private final AccountRepository accountRepository;
    private final ObjectMapper objectMapper;

    public TransferController(
            TransferService transferService,
            IdempotencyService idempotencyService,
            AccountRepository accountRepository,
            ObjectMapper objectMapper
    ) {
        this.transferService = transferService;
        this.idempotencyService = idempotencyService;
        this.accountRepository = accountRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Executes transfer with required Idempotency-Key header.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> initiateTransfer(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @Valid @RequestBody TransferRequest request
    ) {
        // Enforce UUID Idempotency-Key header on all POST requests
        idempotencyService.validateKeyFormat(idempotencyKey);

        // Resolve user ID scoping the idempotency key
        Long userId = headerUserId;
        if (userId == null) {
            Account src = accountRepository.findById(request.sourceAccountId())
                    .orElseThrow(() -> new AccountNotFoundException("Source account " + request.sourceAccountId() + " not found"));
            userId = src.userId() != null ? src.userId() : 0L; // system account default
        }

        // Compute request body SHA-256 hash
        String requestHash = idempotencyService.computeRequestHash(request);

        // Check & acquire idempotency key
        var checkResult = idempotencyService.checkAndAcquire(userId, idempotencyKey, requestHash);
        if (checkResult instanceof IdempotencyService.IdempotencyCheckResult.CachedResponse cached) {
            return ResponseEntity.status(cached.statusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(cached.responseBody());
        }
        if (checkResult instanceof IdempotencyService.IdempotencyCheckResult.Conflict conflict) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ErrorResponse.of("CONCURRENT_REQUEST", conflict.message(), idempotencyKey));
        }

        // Execute transfer inside protected money transaction
        try {
            TransferResponse response = transferService.executeTransfer(request, "User#" + userId);
            idempotencyService.saveSuccess(userId, idempotencyKey, HttpStatus.OK.value(), response);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            idempotencyService.saveFailure(userId, idempotencyKey, HttpStatus.UNPROCESSABLE_ENTITY.value(),
                    ErrorResponse.of("TRANSFER_FAILED", e.getMessage(), idempotencyKey));
            throw e;
        }
    }

    /**
     * Admin release of a held transfer.
     */
    @PostMapping("/{id}/release")
    public ResponseEntity<TransferResponse> releaseTransfer(
            @PathVariable("id") Long id,
            @RequestHeader(value = "X-Admin-User", defaultValue = "Admin") String adminUser
    ) {
        TransferResponse response = transferService.releaseHeldTransfer(id, adminUser);
        return ResponseEntity.ok(response);
    }

    /**
     * Reversal of a completed transfer.
     */
    @PostMapping("/{id}/reverse")
    public ResponseEntity<TransferResponse> reverseTransfer(
            @PathVariable("id") Long id,
            @RequestHeader(value = "X-User-Id", defaultValue = "System") String initiator,
            @RequestParam(value = "reason", required = false) String reason
    ) {
        TransferResponse response = transferService.reverseTransfer(id, initiator, reason);
        return ResponseEntity.ok(response);
    }
}
