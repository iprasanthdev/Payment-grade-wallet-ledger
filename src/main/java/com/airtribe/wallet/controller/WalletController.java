package com.airtribe.wallet.controller;

import com.airtribe.wallet.dto.BalanceResponse;
import com.airtribe.wallet.dto.CreateWalletRequest;
import com.airtribe.wallet.dto.StatementPageResponse;
import com.airtribe.wallet.dto.TransferRequest;
import com.airtribe.wallet.dto.TransferResponse;
import com.airtribe.wallet.dto.WalletResponse;
import com.airtribe.wallet.model.TransferType;
import com.airtribe.wallet.service.AccountService;
import com.airtribe.wallet.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final AccountService accountService;
    private final TransferService transferService;

    public WalletController(AccountService accountService, TransferService transferService) {
        this.accountService = accountService;
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<WalletResponse> createWallet(@Valid @RequestBody CreateWalletRequest request) {
        WalletResponse response = accountService.createWallet(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<WalletResponse> getWallet(@PathVariable("id") Long id) {
        return ResponseEntity.ok(accountService.getWallet(id));
    }

    @GetMapping("/{id}/balance")
    public ResponseEntity<BalanceResponse> getBalance(@PathVariable("id") Long id) {
        return ResponseEntity.ok(accountService.getBalance(id));
    }

    @GetMapping("/{id}/statement")
    public ResponseEntity<StatementPageResponse> getStatement(
            @PathVariable("id") Long id,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "offset", required = false) Integer offset,
            @RequestParam(value = "cursor", required = false) Long cursor
    ) {
        if (cursor != null) {
            return ResponseEntity.ok(accountService.getStatementCursor(id, cursor, limit));
        } else {
            int safeOffset = offset != null ? offset : 0;
            return ResponseEntity.ok(accountService.getStatementOffset(id, limit, safeOffset));
        }
    }

    /**
     * Top-up wallet from System Cash-In Account (id = 1).
     */
    @PostMapping("/{id}/topup")
    public ResponseEntity<TransferResponse> topUpWallet(
            @PathVariable("id") Long id,
            @RequestParam("amount") long amountPaise,
            @RequestParam(value = "note", defaultValue = "Wallet Top-up") String note
    ) {
        TransferRequest request = new TransferRequest(1L, id, amountPaise, TransferType.TOP_UP, note);
        TransferResponse response = transferService.executeTransfer(request, "TopUpAPI");
        return ResponseEntity.ok(response);
    }

    /**
     * Withdraw from wallet to System Cash-Out Account (id = 2).
     */
    @PostMapping("/{id}/withdraw")
    public ResponseEntity<TransferResponse> withdrawFromWallet(
            @PathVariable("id") Long id,
            @RequestParam("amount") long amountPaise,
            @RequestParam(value = "note", defaultValue = "Wallet Withdrawal") String note
    ) {
        TransferRequest request = new TransferRequest(id, 2L, amountPaise, TransferType.WITHDRAWAL, note);
        TransferResponse response = transferService.executeTransfer(request, "WithdrawAPI");
        return ResponseEntity.ok(response);
    }
}
