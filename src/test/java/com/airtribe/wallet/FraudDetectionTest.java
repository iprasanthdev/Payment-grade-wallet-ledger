package com.airtribe.wallet;

import com.airtribe.wallet.dto.TransferRequest;
import com.airtribe.wallet.dto.TransferResponse;
import com.airtribe.wallet.model.Account;
import com.airtribe.wallet.model.AccountType;
import com.airtribe.wallet.model.RiskFlag;
import com.airtribe.wallet.model.TransferStatus;
import com.airtribe.wallet.model.TransferType;
import com.airtribe.wallet.model.User;
import com.airtribe.wallet.repository.AccountRepository;
import com.airtribe.wallet.repository.RiskRepository;
import com.airtribe.wallet.repository.UserRepository;
import com.airtribe.wallet.service.TransferService;
import com.airtribe.wallet.worker.NightlyJobWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
public class FraudDetectionTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private RiskRepository riskRepository;

    @Autowired
    private NightlyJobWorker nightlyJobWorker;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long sourceWalletId;
    private Long destWalletId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE reconciliation_discrepancies, reconciliation_runs, risk_flags, transfer_audit_logs, ledger_entries, transfers, idempotency_keys CASCADE;");
        jdbcTemplate.execute("DELETE FROM accounts WHERE user_id IS NOT NULL;");
        jdbcTemplate.execute("DELETE FROM users CASCADE;");

        User userA = userRepository.create("Fraud Test A", "fraudA@example.com", "+919999944441", "VERIFIED");
        User userB = userRepository.create("Fraud Test B", "fraudB@example.com", "+919999944442", "VERIFIED");

        // Source wallet with ₹25,000 (2,500,000 paise)
        Account walletA = accountRepository.create(userA.id(), AccountType.USER_MAIN, "INR", 2500000L);
        Account walletB = accountRepository.create(userB.id(), AccountType.USER_MAIN, "INR", 0L);

        this.sourceWalletId = walletA.id();
        this.destWalletId = walletB.id();
    }

    /**
     * Milestone 5 Part A:
     * High value transfer (>= ₹10,000 / 1,000,000 paise) triggers HOLD rule.
     * Moves NO money until admin releases it.
     */
    @Test
    @DisplayName("Milestone 5 Part A: High-Value Transfer is HELD and Moves No Money Until Released")
    void testHighValueTransferIsHeldAndReleased() {
        long largeAmount = 1500000L; // ₹15,000 > ₹10,000 threshold

        TransferRequest req = new TransferRequest(sourceWalletId, destWalletId, largeAmount, TransferType.PEER_TRANSFER, "High value payment");
        TransferResponse response = transferService.executeTransfer(req, "UserTester");

        // 1. Status must be HELD
        assertThat(response.status()).isEqualTo(TransferStatus.HELD);

        // 2. NO MONEY MOVED: source balance unchanged, dest balance unchanged!
        long srcBalance = accountRepository.getStoredBalance(sourceWalletId);
        long dstBalance = accountRepository.getStoredBalance(destWalletId);
        assertThat(srcBalance).as("Source wallet must NOT be debited while HELD").isEqualTo(2500000L);
        assertThat(dstBalance).as("Destination wallet must NOT be credited while HELD").isEqualTo(0L);

        // 3. Risk flag created
        List<RiskFlag> flags = riskRepository.findFlagsByTransferId(response.id());
        assertThat(flags).hasSize(1);
        assertThat(flags.get(0).reason()).contains("HIGH_VALUE_TRANSFER");

        // 4. Milestone 5 Part B: Nightly job runs LLM explanation
        int explainedCount = nightlyJobWorker.explainPendingRiskFlags();
        assertThat(explainedCount).isGreaterThanOrEqualTo(1);

        List<RiskFlag> explainedFlags = riskRepository.findFlagsByTransferId(response.id());
        assertThat(explainedFlags.get(0).llmExplanation()).isNotNull();
        assertThat(explainedFlags.get(0).llmLabel()).isNotNull();

        // 5. Admin releases the held transfer
        TransferResponse released = transferService.releaseHeldTransfer(response.id(), "AdminUser");
        assertThat(released.status()).isEqualTo(TransferStatus.COMPLETED);

        // Money has now moved!
        long finalSrcBalance = accountRepository.getStoredBalance(sourceWalletId);
        long finalDstBalance = accountRepository.getStoredBalance(destWalletId);
        assertThat(finalSrcBalance).isEqualTo(1000000L); // ₹25,000 - ₹15,000 = ₹10,000
        assertThat(finalDstBalance).isEqualTo(1500000L); // ₹15,000
    }
}
