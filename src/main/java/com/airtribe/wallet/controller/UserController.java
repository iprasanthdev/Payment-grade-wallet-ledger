package com.airtribe.wallet.controller;

import com.airtribe.wallet.dto.CreateUserRequest;
import com.airtribe.wallet.dto.UserResponse;
import com.airtribe.wallet.dto.WalletResponse;
import com.airtribe.wallet.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/users")
public class UserController {

    private final AccountService accountService;

    public UserController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.ok(accountService.createUser(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUser(@PathVariable("id") Long id) {
        return ResponseEntity.ok(accountService.getUser(id));
    }

    @GetMapping("/{id}/wallets")
    public ResponseEntity<List<WalletResponse>> getUserWallets(@PathVariable("id") Long id) {
        return ResponseEntity.ok(accountService.getWalletsForUser(id));
    }
}
