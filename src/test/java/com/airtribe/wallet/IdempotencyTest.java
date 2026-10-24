package com.airtribe.wallet;

import com.airtribe.wallet.dto.TransferRequest;
import com.airtribe.wallet.dto.TransferResponse;
import com.airtribe.wallet.model.Account;
import com.airtribe.wallet.model.AccountType;
import com.airtribe.wallet.model.TransferType;
import com.airtribe.wallet.model.User;
import com.airtribe.wallet.repository.AccountRepository;
import com.airtribe.wallet.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class IdempotencyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private Long sourceWalletId;
    private Long destWalletId;
    private Long userId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE reconciliation_discrepancies, reconciliation_runs, risk_flags, transfer_audit_logs, ledger_entries, transfers, idempotency_keys CASCADE;");
        jdbcTemplate.execute("DELETE FROM accounts WHERE user_id IS NOT NULL;");
        jdbcTemplate.execute("DELETE FROM users CASCADE;");

        User userA = userRepository.create("Idem User A", "idemA@example.com", "+919999933331", "VERIFIED");
        User userB = userRepository.create("Idem User B", "idemB@example.com", "+919999933332", "VERIFIED");

        Account walletA = accountRepository.create(userA.id(), AccountType.USER_MAIN, "INR", 100000L); // ₹1,000
        Account walletB = accountRepository.create(userB.id(), AccountType.USER_MAIN, "INR", 0L);

        this.userId = userA.id();
        this.sourceWalletId = walletA.id();
        this.destWalletId = walletB.id();
    }

    /**
     * Requirement: Missing Idempotency-Key returns 400 Bad Request.
     */
    @Test
    @DisplayName("Milestone 3: Missing Idempotency-Key Header Returns 400")
    void testMissingIdempotencyKeyReturns400() throws Exception {
        TransferRequest req = new TransferRequest(sourceWalletId, destWalletId, 5000L, TransferType.PEER_TRANSFER, "Test");
        mockMvc.perform(post("/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    /**
     * Acceptance Test 1:
     * The same request sent 5 times in a row creates 1 transfer and returns 5 identical responses.
     */
    @Test
    @DisplayName("Milestone 3: Same Request Sent 5 Times Creates 1 Transfer and Returns Identical Responses")
    void testSequentialReplayCreatesSingleTransfer() throws Exception {
        String key = UUID.randomUUID().toString();
        TransferRequest req = new TransferRequest(sourceWalletId, destWalletId, 10000L, TransferType.PEER_TRANSFER, "Coffee Payment");
        String payload = objectMapper.writeValueAsString(req);

        List<String> responseBodies = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            MvcResult result = mockMvc.perform(post("/transfers")
                            .header("Idempotency-Key", key)
                            .header("X-User-Id", userId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isOk())
                    .andReturn();
            responseBodies.add(result.getResponse().getContentAsString());
        }

        // All 5 responses must be identical
        String firstResponse = responseBodies.get(0);
        for (int i = 1; i < responseBodies.size(); i++) {
            assertThat(responseBodies.get(i))
                    .as("Response %d must be byte-for-byte identical to original response", i + 1)
                    .isEqualTo(firstResponse);
        }

        // Exactly 1 transfer record created in database
        Integer transferCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transfers", Integer.class);
        assertThat(transferCount).isEqualTo(1);

        // Wallet was debited exactly once (₹100 = 10000 paise)
        long balance = accountRepository.getStoredBalance(sourceWalletId);
        assertThat(balance).isEqualTo(90000L);
    }

    /**
     * Acceptance Test 2:
     * The same key sent 10 times in parallel still creates exactly 1 transfer.
     */
    @Test
    @DisplayName("Milestone 3: Same Key Sent 10 Times in Parallel Creates Exactly 1 Transfer")
    void testConcurrentIdempotentRequests() throws InterruptedException {
        String key = UUID.randomUUID().toString();
        TransferRequest req = new TransferRequest(sourceWalletId, destWalletId, 5000L, TransferType.PEER_TRANSFER, "Concurrent Replay");
        String payload;
        try {
            payload = objectMapper.writeValueAsString(req);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        ExecutorService executor = Executors.newFixedThreadPool(10);
        AtomicInteger okCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> {
                try {
                    MvcResult res = mockMvc.perform(post("/transfers")
                                    .header("Idempotency-Key", key)
                                    .header("X-User-Id", userId)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payload))
                            .andReturn();
                    int status = res.getResponse().getStatus();
                    if (status == 200) {
                        okCount.incrementAndGet();
                    } else if (status == 409) {
                        conflictCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Ignore
                }
                return null;
            });
        }

        executor.invokeAll(tasks);
        executor.shutdown();

        // Exactly 1 transfer row in database
        Integer transferCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transfers", Integer.class);
        assertThat(transferCount).isEqualTo(1);

        // Source balance debited only once (₹50 = 5000 paise)
        long finalBalance = accountRepository.getStoredBalance(sourceWalletId);
        assertThat(finalBalance).isEqualTo(95000L);
    }

    /**
     * Acceptance Test 3:
     * The same key with a different amount returns 422, and no money moves.
     */
    @Test
    @DisplayName("Milestone 3: Same Key with Different Amount Returns 422 and No Money Moves")
    void testKeyReusedWithDifferentPayloadReturns422() throws Exception {
        String key = UUID.randomUUID().toString();

        // First request: ₹100
        TransferRequest req1 = new TransferRequest(sourceWalletId, destWalletId, 10000L, TransferType.PEER_TRANSFER, "First Request");
        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", key)
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isOk());

        long balanceAfterFirst = accountRepository.getStoredBalance(sourceWalletId);
        assertThat(balanceAfterFirst).isEqualTo(90000L);

        // Second request with SAME KEY but DIFFERENT AMOUNT: ₹200
        TransferRequest req2 = new TransferRequest(sourceWalletId, destWalletId, 20000L, TransferType.PEER_TRANSFER, "Modified Request");
        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", key)
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        // Verify NO MONEY MOVED on second request!
        long balanceAfterSecond = accountRepository.getStoredBalance(sourceWalletId);
        assertThat(balanceAfterSecond).isEqualTo(90000L);

        // Still exactly 1 transfer in database
        Integer transferCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transfers", Integer.class);
        assertThat(transferCount).isEqualTo(1);
    }
}
