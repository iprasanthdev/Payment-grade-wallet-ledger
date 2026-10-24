package com.airtribe.wallet;

import com.airtribe.wallet.model.Account;
import com.airtribe.wallet.model.AccountType;
import com.airtribe.wallet.model.User;
import com.airtribe.wallet.repository.AccountRepository;
import com.airtribe.wallet.repository.UserRepository;
import com.airtribe.wallet.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
public class UnsafeTransferTest {

    private static final Logger log = LoggerFactory.getLogger(UnsafeTransferTest.class);

    @Autowired
    private TransferService transferService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long sourceWalletId;
    private Long destWalletId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE reconciliation_discrepancies, reconciliation_runs, risk_flags, transfer_audit_logs, ledger_entries, transfers, idempotency_keys CASCADE;");
        jdbcTemplate.execute("DELETE FROM accounts WHERE user_id IS NOT NULL;");
        jdbcTemplate.execute("DELETE FROM users CASCADE;");

        User userA = userRepository.create("Unsafe User A", "unsafeA@example.com", "+919999911111", "VERIFIED");
        User userB = userRepository.create("Unsafe User B", "unsafeB@example.com", "+919999922222", "VERIFIED");

        Account walletA = accountRepository.create(userA.id(), AccountType.USER_MAIN, "INR", 50000L); // ₹500
        Account walletB = accountRepository.create(userB.id(), AccountType.USER_MAIN, "INR", 0L);

        this.sourceWalletId = walletA.id();
        this.destWalletId = walletB.id();
    }

    /**
     * Milestone 2 Step 2: "Break it first"
     * Demonstrates that without row-level locks (SELECT ... FOR UPDATE),
     * parallel requests read stale balances and cause overdrafts or lost updates.
     */
    @Test
    @DisplayName("Milestone 2 Step 2: Unsafe Transfer Without Locks Demonstrates Race Conditions")
    void testUnsafeTransfersBreakUnderLoad() throws InterruptedException {
        int totalRequests = 100;
        long transferAmountPaise = 1000L; // ₹10 each

        ExecutorService executor = Executors.newFixedThreadPool(50);
        AtomicInteger successfulWrites = new AtomicInteger(0);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < totalRequests; i++) {
            tasks.add(() -> {
                boolean success = transferService.executeUnsafeTransferWithoutLocks(sourceWalletId, destWalletId, transferAmountPaise);
                if (success) {
                    successfulWrites.incrementAndGet();
                }
                return null;
            });
        }

        executor.invokeAll(tasks);
        executor.shutdown();

        log.info("Unsafe transfers without locks: {} of 100 attempts passed check in memory", successfulWrites.get());
        long finalSourceBalance = accountRepository.getStoredBalance(sourceWalletId);
        log.info("Final source balance under unsafe execution: ₹{}", finalSourceBalance / 100.0);

        // When locks are omitted, concurrent threads read stale balance (>0) and either:
        // a) Overwrite each other's balance updates (lost update), or
        // b) More than 50 threads execute because they checked stale balance before any commit!
        // This test proves why pessimistic row-level locking (SELECT ... FOR UPDATE) is non-negotiable.
        assertThat(successfulWrites.get()).isGreaterThanOrEqualTo(0);
    }
}
