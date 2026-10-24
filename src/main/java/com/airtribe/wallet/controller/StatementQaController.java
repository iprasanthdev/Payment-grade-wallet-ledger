package com.airtribe.wallet.controller;

import com.airtribe.wallet.dto.StatementAskRequest;
import com.airtribe.wallet.dto.StatementAskResponse;
import com.airtribe.wallet.service.StatementQaService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users/{id}/statement")
public class StatementQaController {

    private final StatementQaService statementQaService;

    public StatementQaController(StatementQaService statementQaService) {
        this.statementQaService = statementQaService;
    }

    /**
     * Milestone 5 Part C: Natural language statement Q&A.
     */
    @PostMapping("/ask")
    public ResponseEntity<StatementAskResponse> askStatementQuestion(
            @PathVariable("id") Long userId,
            @Valid @RequestBody StatementAskRequest request
    ) {
        StatementAskResponse response = statementQaService.answerQuestion(userId, request.question());
        return ResponseEntity.ok(response);
    }
}
