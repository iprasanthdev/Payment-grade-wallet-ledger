package com.airtribe.wallet.service;

import com.airtribe.wallet.dto.BalanceResponse;
import com.airtribe.wallet.dto.CreateUserRequest;
import com.airtribe.wallet.dto.CreateWalletRequest;
import com.airtribe.wallet.dto.StatementEntryResponse;
import com.airtribe.wallet.dto.StatementPageResponse;
import com.airtribe.wallet.dto.UserResponse;
import com.airtribe.wallet.dto.WalletResponse;
import com.airtribe.wallet.exception.AccountNotFoundException;
import com.airtribe.wallet.exception.UserNotFoundException;
import com.airtribe.wallet.model.Account;
import com.airtribe.wallet.model.AccountType;
import com.airtribe.wallet.model.LedgerEntry;
import com.airtribe.wallet.model.User;
import com.airtribe.wallet.repository.AccountRepository;
import com.airtribe.wallet.repository.LedgerEntryRepository;
import com.airtribe.wallet.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AccountService {

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public AccountService(
            UserRepository userRepository,
            AccountRepository accountRepository,
            LedgerEntryRepository ledgerEntryRepository
    ) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    public UserResponse createUser(CreateUserRequest request) {
        User user = userRepository.create(request.name(), request.email(), request.phone(), "VERIFIED");
        return new UserResponse(user.id(), user.name(), user.email(), user.phone(), user.kycStatus(), user.createdAt());
    }

    public UserResponse getUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User " + userId + " not found"));
        return new UserResponse(user.id(), user.name(), user.email(), user.phone(), user.kycStatus(), user.createdAt());
    }

    public WalletResponse createWallet(CreateWalletRequest request) {
        userRepository.findById(request.userId())
                .orElseThrow(() -> new UserNotFoundException("User " + request.userId() + " not found"));

        AccountType type = request.accountType() != null ? request.accountType() : AccountType.USER_MAIN;
        Account account = accountRepository.create(request.userId(), type, "INR", 0);
        return WalletResponse.from(account.id(), account.userId(), account.accountType(), account.currency(), account.balance(), account.status(), account.createdAt());
    }

    public WalletResponse getWallet(Long walletId) {
        Account account = accountRepository.findById(walletId)
                .orElseThrow(() -> new AccountNotFoundException("Wallet " + walletId + " not found"));
        return WalletResponse.from(account.id(), account.userId(), account.accountType(), account.currency(), account.balance(), account.status(), account.createdAt());
    }

    public BalanceResponse getBalance(Long walletId) {
        Account account = accountRepository.findById(walletId)
                .orElseThrow(() -> new AccountNotFoundException("Wallet " + walletId + " not found"));
        return BalanceResponse.of(account.id(), account.balance(), account.currency());
    }

    public List<WalletResponse> getWalletsForUser(Long userId) {
        return accountRepository.findByUserId(userId).stream()
                .map(a -> WalletResponse.from(a.id(), a.userId(), a.accountType(), a.currency(), a.balance(), a.status(), a.createdAt()))
                .toList();
    }

    /**
     * Offset-based statement pagination:
     * ORDER BY created_at DESC, id DESC LIMIT ... OFFSET ...
     */
    public StatementPageResponse getStatementOffset(Long walletId, int limit, int offset) {
        accountRepository.findById(walletId)
                .orElseThrow(() -> new AccountNotFoundException("Wallet " + walletId + " not found"));

        int safeLimit = Math.min(Math.max(limit, 1), 100);
        int safeOffset = Math.max(offset, 0);

        List<LedgerEntry> entries = ledgerEntryRepository.findByAccountIdOffset(walletId, safeLimit, safeOffset);
        List<StatementEntryResponse> responseEntries = entries.stream()
                .map(e -> StatementEntryResponse.from(e.id(), e.transferId(), e.accountId(), e.direction(), e.amount(), e.createdAt()))
                .toList();

        Long nextCursor = entries.isEmpty() ? null : entries.get(entries.size() - 1).id();
        boolean hasMore = entries.size() == safeLimit;

        return new StatementPageResponse(walletId, responseEntries, safeLimit, safeOffset, nextCursor, hasMore);
    }

    /**
     * Cursor-based statement pagination on (created_at DESC, id DESC).
     * Eliminates performance degradation of OFFSET on massive tables.
     */
    public StatementPageResponse getStatementCursor(Long walletId, Long cursorId, int limit) {
        accountRepository.findById(walletId)
                .orElseThrow(() -> new AccountNotFoundException("Wallet " + walletId + " not found"));

        int safeLimit = Math.min(Math.max(limit, 1), 100);
        List<LedgerEntry> entries = ledgerEntryRepository.findByAccountIdCursor(walletId, cursorId, safeLimit);
        List<StatementEntryResponse> responseEntries = entries.stream()
                .map(e -> StatementEntryResponse.from(e.id(), e.transferId(), e.accountId(), e.direction(), e.amount(), e.createdAt()))
                .toList();

        Long nextCursor = entries.isEmpty() ? null : entries.get(entries.size() - 1).id();
        boolean hasMore = entries.size() == safeLimit;

        return new StatementPageResponse(walletId, responseEntries, safeLimit, 0, nextCursor, hasMore);
    }
}
