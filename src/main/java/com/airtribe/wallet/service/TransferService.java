package com.airtribe.wallet.service;

import com.airtribe.wallet.dto.TransferRequest;
import com.airtribe.wallet.dto.TransferResponse;
import com.airtribe.wallet.exception.AccountNotFoundException;
import com.airtribe.wallet.exception.InsufficientFundsException;
import com.airtribe.wallet.exception.WalletException;
import com.airtribe.wallet.model.Account;
import com.airtribe.wallet.model.LedgerDirection;
import com.airtribe.wallet.model.Transfer;
import com.airtribe.wallet.model.TransferStatus;
import com.airtribe.wallet.model.TransferType;
import com.airtribe.wallet.repository.AccountRepository;
import com.airtribe.wallet.repository.AuditLogRepository;
import com.airtribe.wallet.repository.LedgerEntryRepository;
import com.airtribe.wallet.repository.RiskRepository;
import com.airtribe.wallet.repository.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final RiskRepository riskRepository;
    private final AuditLogRepository auditLogRepository;
    private final FraudDetectionService fraudDetectionService;
    private final TransactionTemplate transactionTemplate;

    public TransferService(
            AccountRepository accountRepository,
            TransferRepository transferRepository,
            LedgerEntryRepository ledgerEntryRepository,
            RiskRepository riskRepository,
            AuditLogRepository auditLogRepository,
            FraudDetectionService fraudDetectionService,
            PlatformTransactionManager transactionManager
    ) {
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.riskRepository = riskRepository;
        this.auditLogRepository = auditLogRepository;
        this.fraudDetectionService = fraudDetectionService;

        // Money path transaction: Read Committed isolation with explicit row-level locks
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    /**
     * =========================================================================
     * CORE MONEY PATH: SAFE CONCURRENT TRANSFER EXECUTION
     * =========================================================================
     * Explicit transaction lifecycle:
     * 1. Assess fraud risk rules before touching accounts.
     *    - If flagged for HOLD: Create transfer in HELD state, move NO money, return.
     * 2. Lock both source and destination account rows with SELECT ... FOR UPDATE
     *    in deterministic ASCENDING order (prevents deadlocks).
     * 3. Re-read source balance under lock; reject if balance < transfer amount.
     * 4. Insert transfer record (status = COMPLETED).
     * 5. Insert double-entry ledger lines (DEBIT on source, CREDIT on destination).
     *    Enforces: Debits sum == Credits sum.
     * 6. Update stored balances for source and destination accounts.
     * 7. Record risk flags & audit trail.
     * 8. Transaction commits atomically. On failure, everything rolls back.
     */
    public TransferResponse executeTransfer(TransferRequest request, String initiator) {
        Long srcId = request.sourceAccountId();
        Long dstId = request.destinationAccountId();
        long amount = request.amountPaise();
        TransferType type = request.transferType() != null ? request.transferType() : TransferType.PEER_TRANSFER;

        if (srcId.equals(dstId)) {
            throw new WalletException("INVALID_TRANSFER", "Source and destination accounts cannot be identical", HttpStatus.BAD_REQUEST);
        }
        if (amount <= 0) {
            throw new WalletException("INVALID_AMOUNT", "Transfer amount must be strictly greater than 0 paise", HttpStatus.BAD_REQUEST);
        }

        // Step 1: Fraud assessment
        FraudDetectionService.RiskAssessment assessment = fraudDetectionService.assessTransfer(srcId, amount);

        return transactionTemplate.execute(status -> {
            // If transfer is flagged for HOLD, record as HELD without debiting or crediting accounts
            if (assessment.shouldHold()) {
                log.warn("Transfer from account {} to {} (₹{}) is HELD by risk rules", srcId, dstId, amount / 100.0);
                Transfer heldTransfer = transferRepository.createTransfer(
                        type,
                        TransferStatus.HELD,
                        amount,
                        srcId,
                        dstId,
                        null,
                        request.note() != null ? request.note() : "Held for risk review"
                );

                // Record risk flags
                for (var triggered : assessment.triggeredRules()) {
                    riskRepository.createFlag(heldTransfer.id(), triggered.rule().id(), triggered.reason());
                }

                // Log audit trail
                auditLogRepository.logStatusChange(heldTransfer.id(), null, TransferStatus.HELD.name(), initiator, "Triggered fraud hold rule");

                return TransferResponse.from(
                        heldTransfer.id(),
                        heldTransfer.transferType(),
                        heldTransfer.status(),
                        heldTransfer.amount(),
                        heldTransfer.sourceAccountId(),
                        heldTransfer.destinationAccountId(),
                        heldTransfer.note(),
                        heldTransfer.createdAt()
                );
            }

            // Step 2: Acquire pessimistic row locks in ASCENDING ID order (DEADLOCK AVOIDANCE)
            List<Long> orderedIds = List.of(Math.min(srcId, dstId), Math.max(srcId, dstId));
            List<Account> lockedAccounts = accountRepository.lockAccountsForUpdate(orderedIds);
            Map<Long, Account> accountMap = lockedAccounts.stream()
                    .collect(Collectors.toMap(Account::id, Function.identity()));

            Account sourceAccount = accountMap.get(srcId);
            Account destAccount = accountMap.get(dstId);

            if (sourceAccount == null) {
                throw new AccountNotFoundException("Source account " + srcId + " does not exist");
            }
            if (destAccount == null) {
                throw new AccountNotFoundException("Destination account " + dstId + " does not exist");
            }
            if (!"ACTIVE".equalsIgnoreCase(sourceAccount.status())) {
                throw new WalletException("ACCOUNT_INACTIVE", "Source account is " + sourceAccount.status(), HttpStatus.BAD_REQUEST);
            }
            if (!"ACTIVE".equalsIgnoreCase(destAccount.status())) {
                throw new WalletException("ACCOUNT_INACTIVE", "Destination account is " + destAccount.status(), HttpStatus.BAD_REQUEST);
            }

            // Step 3: Re-read balance under lock and enforce non-negative constraint for user accounts
            if (sourceAccount.isUserAccount() && sourceAccount.balance() < amount) {
                log.warn("Insufficient funds for account {}: requested ₹{}, available ₹{}",
                        srcId, amount / 100.0, sourceAccount.balance() / 100.0);
                throw new InsufficientFundsException(
                        String.format("Insufficient funds in wallet %d. Available: ₹%.2f, Required: ₹%.2f",
                                srcId, sourceAccount.balance() / 100.0, amount / 100.0)
                );
            }

            // Step 4: Insert the transfer event (status = COMPLETED)
            Transfer transfer = transferRepository.createTransfer(
                    type,
                    TransferStatus.COMPLETED,
                    amount,
                    srcId,
                    dstId,
                    null,
                    request.note()
            );

            // Step 5: Write balanced double-entry ledger lines (immutable)
            // DEBIT on source account (money out)
            ledgerEntryRepository.createEntry(transfer.id(), srcId, LedgerDirection.DEBIT, amount);
            // CREDIT on destination account (money in)
            ledgerEntryRepository.createEntry(transfer.id(), dstId, LedgerDirection.CREDIT, amount);

            // Step 6: Update stored balances
            accountRepository.updateBalance(srcId, -amount);
            accountRepository.updateBalance(dstId, amount);

            // Step 7: Record any non-blocking risk flags
            for (var triggered : assessment.triggeredRules()) {
                riskRepository.createFlag(transfer.id(), triggered.rule().id(), triggered.reason());
            }

            // Record audit trail
            auditLogRepository.logStatusChange(transfer.id(), null, TransferStatus.COMPLETED.name(), initiator, "Transfer completed successfully");

            log.info("Completed transfer {}: ₹{} from account {} to account {}",
                    transfer.id(), amount / 100.0, srcId, dstId);

            return TransferResponse.from(
                    transfer.id(),
                    transfer.transferType(),
                    transfer.status(),
                    transfer.amount(),
                    transfer.sourceAccountId(),
                    transfer.destinationAccountId(),
                    transfer.note(),
                    transfer.createdAt()
            );
        });
    }

    /**
     * Admin release of a held transfer.
     */
    public TransferResponse releaseHeldTransfer(Long transferId, String adminUser) {
        return transactionTemplate.execute(status -> {
            Transfer transfer = transferRepository.findById(transferId)
                    .orElseThrow(() -> new WalletException("NOT_FOUND", "Transfer " + transferId + " not found", HttpStatus.NOT_FOUND));

            if (transfer.status() != TransferStatus.HELD) {
                throw new WalletException("INVALID_STATE", "Transfer is not in HELD status. Current status: " + transfer.status(), HttpStatus.BAD_REQUEST);
            }

            Long srcId = transfer.sourceAccountId();
            Long dstId = transfer.destinationAccountId();
            long amount = transfer.amount();

            // Lock accounts in order
            List<Long> orderedIds = List.of(Math.min(srcId, dstId), Math.max(srcId, dstId));
            List<Account> lockedAccounts = accountRepository.lockAccountsForUpdate(orderedIds);
            Map<Long, Account> accountMap = lockedAccounts.stream().collect(Collectors.toMap(Account::id, Function.identity()));

            Account sourceAccount = accountMap.get(srcId);
            if (sourceAccount.isUserAccount() && sourceAccount.balance() < amount) {
                throw new InsufficientFundsException("Insufficient funds to release held transfer " + transferId);
            }

            // Write double-entry entries
            ledgerEntryRepository.createEntry(transfer.id(), srcId, LedgerDirection.DEBIT, amount);
            ledgerEntryRepository.createEntry(transfer.id(), dstId, LedgerDirection.CREDIT, amount);

            // Update balances
            accountRepository.updateBalance(srcId, -amount);
            accountRepository.updateBalance(dstId, amount);

            // Update status
            transferRepository.updateStatus(transfer.id(), TransferStatus.COMPLETED);
            auditLogRepository.logStatusChange(transfer.id(), TransferStatus.HELD.name(), TransferStatus.COMPLETED.name(), adminUser, "Released by administrator");

            return TransferResponse.from(
                    transfer.id(),
                    transfer.transferType(),
                    TransferStatus.COMPLETED,
                    transfer.amount(),
                    srcId,
                    dstId,
                    transfer.note(),
                    transfer.createdAt()
            );
        });
    }

    /**
     * Reversal of a completed transfer (creates a new counter-transfer pointing to original).
     */
    public TransferResponse reverseTransfer(Long originalTransferId, String initiator, String reason) {
        return transactionTemplate.execute(status -> {
            Transfer original = transferRepository.findById(originalTransferId)
                    .orElseThrow(() -> new WalletException("NOT_FOUND", "Transfer " + originalTransferId + " not found", HttpStatus.NOT_FOUND));

            if (original.status() != TransferStatus.COMPLETED) {
                throw new WalletException("INVALID_STATE", "Only COMPLETED transfers can be reversed", HttpStatus.BAD_REQUEST);
            }

            // Reversal inverts source and destination
            Long revSrcId = original.destinationAccountId();
            Long revDstId = original.sourceAccountId();
            long amount = original.amount();

            List<Long> orderedIds = List.of(Math.min(revSrcId, revDstId), Math.max(revSrcId, revDstId));
            List<Account> lockedAccounts = accountRepository.lockAccountsForUpdate(orderedIds);
            Map<Long, Account> accountMap = lockedAccounts.stream().collect(Collectors.toMap(Account::id, Function.identity()));

            Account revSrc = accountMap.get(revSrcId);
            if (revSrc.isUserAccount() && revSrc.balance() < amount) {
                throw new InsufficientFundsException("Insufficient funds in account " + revSrcId + " to perform reversal");
            }

            Transfer reversal = transferRepository.createTransfer(
                    TransferType.REVERSAL,
                    TransferStatus.COMPLETED,
                    amount,
                    revSrcId,
                    revDstId,
                    originalTransferId,
                    "Reversal of transfer #" + originalTransferId + ": " + (reason != null ? reason : "administrative reversal")
            );

            ledgerEntryRepository.createEntry(reversal.id(), revSrcId, LedgerDirection.DEBIT, amount);
            ledgerEntryRepository.createEntry(reversal.id(), revDstId, LedgerDirection.CREDIT, amount);

            accountRepository.updateBalance(revSrcId, -amount);
            accountRepository.updateBalance(revDstId, amount);

            transferRepository.updateStatus(original.id(), TransferStatus.REVERSED);

            auditLogRepository.logStatusChange(original.id(), TransferStatus.COMPLETED.name(), TransferStatus.REVERSED.name(), initiator, "Reversed by transfer #" + reversal.id());
            auditLogRepository.logStatusChange(reversal.id(), null, TransferStatus.COMPLETED.name(), initiator, "Created as reversal of #" + original.id());

            return TransferResponse.from(
                    reversal.id(),
                    reversal.transferType(),
                    reversal.status(),
                    reversal.amount(),
                    revSrcId,
                    revDstId,
                    reversal.note(),
                    reversal.createdAt()
            );
        });
    }

    /**
     * STEP 2 "BREAK IT FIRST" UNPROTECTED TRANSFER (FOR DEMONSTRATION & TEST COMPARISON ONLY).
     * Reads balance, checks in code, and writes WITHOUT acquiring row locks.
     * Demonstrates race conditions, lost updates, and overdrafts under concurrency.
     */
    public boolean executeUnsafeTransferWithoutLocks(Long srcId, Long dstId, long amount) {
        try {
            // 1. Read balance without lock
            long balance = accountRepository.getStoredBalance(srcId);
            // 2. Check in code
            if (balance >= amount) {
                // Simulate small context switch delay to provoke race conditions
                try { Thread.sleep(2); } catch (InterruptedException ignored) {}
                // 3. Write update directly
                accountRepository.updateBalance(srcId, -amount);
                accountRepository.updateBalance(dstId, amount);
                return true;
            }
            return false;
        } catch (Exception e) {
            // DB constraint will catch overdraft attempts when balance drops below 0
            return false;
        }
    }
}
