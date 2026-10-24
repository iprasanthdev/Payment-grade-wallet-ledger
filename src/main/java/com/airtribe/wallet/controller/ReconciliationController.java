package com.airtribe.wallet.controller;

import com.airtribe.wallet.model.ReconciliationRun;
import com.airtribe.wallet.service.ReconciliationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/reconciliation")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    /**
     * Milestone 4: Manual trigger for the nightly reconciliation audit.
     */
    @PostMapping("/run")
    public ResponseEntity<ReconciliationService.AuditResult> triggerAudit() {
        ReconciliationService.AuditResult result = reconciliationService.runNightlyAudit();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/runs")
    public ResponseEntity<List<ReconciliationRun>> getRecentRuns(@RequestParam(value = "limit", defaultValue = "20") int limit) {
        return ResponseEntity.ok(reconciliationService.getRecentRuns(limit));
    }
}
