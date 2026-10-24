package com.airtribe.wallet;

import com.airtribe.wallet.dto.TransferRequest;
import com.airtribe.wallet.dto.TransferResponse;
import com.airtribe.wallet.exception.InsufficientFundsException;
import com.airtribe.wallet.model.Account;
import com.airtribe.wallet.model.AccountType;
import com.airtribe.wallet.model.TransferType;
import com.airtribe.wallet.model.User;
import com.airtribe.wallet.repository.AccountRepository;
import com.airtribe.wallet.repository.LedgerEntryRepository;
import com.airtribe.wallet.repository.UserRepository;
import com.airtribe.wallet.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
public class StampedeTest {

    private static final Logger log = LoggerFactory.getLogger(StampedeTest.class);

    @Autowired
    private TransferService transferService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long sourceWalletId;
    private Long destWalletId;

    @BeforeEach
    void setUp() {
        // Clean test tables using TRUNCATE CASCADE (bypasses row delete trigger)
        jdbcTemplate.execute("TRUNCATE TABLE reconciliation_discrepancies, reconciliation_runs, risk_flags, transfer_audit_logs, ledger_entries, transfers, idempotency_keys CASCADE;");
        jdbcTemplate.execute("DELETE FROM accounts WHERE user_id IS NOT NULL;");
        jdbcTemplate.execute("DELETE FROM users CASCADE;");

        // Create 2 test users
        User userA = userRepository.create("Asha Stampede", "asha.stampede@example.com", "+919999900001", "VERIFIED");
        User userB = userRepository.create("Rahul Stampede", "rahul.stampede@example.com", "+919999900002", "VERIFIED");

        // Create source wallet and destination wallet with 0 balance
        Account walletA = accountRepository.create(userA.id(), AccountType.USER_MAIN, "INR", 0L);
        Account walletB = accountRepository.create(userB.id(), AccountType.USER_MAIN, "INR", 0L);

        // Top-up source wallet with ₹500.00 (50,000 paise) from SYSTEM_CASH_IN (Account 1)
        transferService.executeTransfer(
                new TransferRequest(1L, walletA.id(), 50000L, TransferType.TOP_UP, "Initial Top-up"),
                "SetupRunner"
        );

        this.sourceWalletId = walletA.id();
        this.destWalletId = walletB.id();
    }

    /**
     * Milestone 2 Acceptance Test: The Stampede
     * Put ₹500 in one wallet and fire 100 concurrent ₹10 transfers out of it.
     * Passes only if:
     * - Exactly 50 transfers succeed
     * - The wallet ends at ₹0 and is never negative
     * - Total debits equal total credits
     * Run 20 times in a row!
     */
    @RepeatedTest(value = 20, name = "Stampede Run {currentRepetition} of {totalRepetitions}")
    @DisplayName("Milestone 2 Acceptance Test: 100 Concurrent Transfers against ₹500 Wallet")
    void testStampedeConcurrency() throws InterruptedException {
        int totalRequests = 100;
        long transferAmountPaise = 1000L; // ₹10.00 each

        ExecutorService executor = Executors.newFixedThreadPool(50);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < totalRequests; i++) {
            tasks.add(() -> {
                TransferRequest request = new TransferRequest(
                        sourceWalletId,
                        destWalletId,
                        transferAmountPaise,
                        TransferType.PEER_TRANSFER,
                        "Stampede payment #" + UUID.randomUUID()
                );
                try {
                    TransferResponse response = transferService.executeTransfer(request, "StampedeRunner");
                    if ("COMPLETED".equals(response.status().name())) {
                        successCount.incrementAndGet();
                    }
                } catch (InsufficientFundsException e) {
                    failureCount.incrementAndGet();
                } catch (Exception e) {
                    log.error("Unexpected error in stampede transfer", e);
                }
                return null;
            });
        }

        // Fire all 100 requests concurrently
        List<Future<Void>> futures = executor.invokeAll(tasks);
        for (Future<Void> future : futures) {
            try {
                future.get();
            } catch (ExecutionException e) {
                // Ignore
            }
        }
        executor.shutdown();

        // 1. Verify exactly 50 transfers succeeded and 50 failed
        assertThat(successCount.get())
                .as("Exactly 50 transfers of ₹10 must succeed from ₹500 balance")
                .isEqualTo(50);
        assertThat(failureCount.get())
                .as("Exactly 50 transfers must fail with Insufficient Funds")
                .isEqualTo(50);

        // 2. Verify source wallet ends at exactly ₹0 and is never negative
        long finalSourceBalance = accountRepository.getStoredBalance(sourceWalletId);
        long computedSourceBalance = ledgerEntryRepository.computeBalanceForAccount(sourceWalletId);

        assertThat(finalSourceBalance)
                .as("Stored wallet balance must end at exactly 0 paise")
                .isEqualTo(0L);
        assertThat(computedSourceBalance)
                .as("Computed ledger balance must end at exactly 0 paise")
                .isEqualTo(0L);

        // Destination wallet must have received exactly ₹500 (50,000 paise)
        long finalDestBalance = accountRepository.getStoredBalance(destWalletId);
        assertThat(finalDestBalance).isEqualTo(50000L);

        // 3. Verify total debits equal total credits
        Long totalDebits = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE direction = 'DEBIT'", Long.class);
        Long totalCredits = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE direction = 'CREDIT'", Long.class);

        assertThat(totalDebits).isNotNull();
        assertThat(totalCredits).isNotNull();
        assertThat(totalDebits).as("Total debits must equal total credits").isEqualTo(totalCredits);
        assertThat(totalDebits).isEqualTo(50000L + (50 * transferAmountPaise));
    }
}
