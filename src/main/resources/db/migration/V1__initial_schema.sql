-- ==============================================================================
-- Payment-Grade Wallet Ledger Schema
-- Milestone 1: Robust Double-Entry Financial Schema
-- ==============================================================================

-- 1. Users Table
CREATE TABLE users (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    phone VARCHAR(50) NOT NULL UNIQUE,
    kyc_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' 
        CHECK (kyc_status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 2. Accounts Table (User Wallets + System Accounts)
-- System accounts (Cash-in, Cash-out, Fee revenue) have user_id = NULL.
CREATE TABLE accounts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE RESTRICT,
    account_type VARCHAR(30) NOT NULL 
        CHECK (account_type IN ('USER_MAIN', 'USER_SAVINGS', 'SYSTEM_CASH_IN', 'SYSTEM_CASH_OUT', 'SYSTEM_FEE_REVENUE')),
    currency VARCHAR(3) NOT NULL DEFAULT 'INR' 
        CHECK (currency = 'INR'),
    balance BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' 
        CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Database Constraint: User wallets cannot have negative balance. System accounts can go negative.
    CONSTRAINT chk_user_wallet_non_negative CHECK (user_id IS NULL OR balance >= 0)
);

-- 3. Transfers Table (The Business Event)
CREATE TABLE transfers (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_type VARCHAR(30) NOT NULL 
        CHECK (transfer_type IN ('TOP_UP', 'PEER_TRANSFER', 'WITHDRAWAL', 'FEE', 'REVERSAL')),
    status VARCHAR(20) NOT NULL 
        CHECK (status IN ('PENDING', 'COMPLETED', 'HELD', 'REJECTED', 'REVERSED')),
    amount BIGINT NOT NULL 
        CHECK (amount > 0),
    source_account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    destination_account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    related_transfer_id BIGINT REFERENCES transfers(id) ON DELETE RESTRICT,
    note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 4. Ledger Entries Table (Double-Entry Bookkeeping Lines)
CREATE TABLE ledger_entries (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id BIGINT NOT NULL REFERENCES transfers(id) ON DELETE RESTRICT,
    account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    direction VARCHAR(6) NOT NULL 
        CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount BIGINT NOT NULL 
        CHECK (amount > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Immutable Ledger Protection: Prevent UPDATE or DELETE on ledger_entries
CREATE OR REPLACE FUNCTION prevent_ledger_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Ledger entries are strictly immutable and cannot be updated or deleted. transfer_id=%, entry_id=%', OLD.transfer_id, OLD.id;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_no_update_delete_ledger_entries
BEFORE UPDATE OR DELETE ON ledger_entries
FOR EACH ROW
EXECUTE FUNCTION prevent_ledger_mutation();

-- 5. Idempotency Keys Table (Safe Retries)
CREATE TABLE idempotency_keys (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL 
        CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED')),
    response_code INTEGER,
    response_body TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_idempotency_user_key UNIQUE (user_id, key)
);

-- 6. Risk Rules Table
CREATE TABLE risk_rules (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rule_name VARCHAR(100) NOT NULL UNIQUE,
    rule_type VARCHAR(50) NOT NULL,
    threshold_value BIGINT NOT NULL,
    action VARCHAR(20) NOT NULL 
        CHECK (action IN ('HOLD', 'FLAG')),
    description TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 7. Risk Flags Table
CREATE TABLE risk_flags (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id BIGINT NOT NULL REFERENCES transfers(id) ON DELETE RESTRICT,
    rule_id BIGINT NOT NULL REFERENCES risk_rules(id) ON DELETE RESTRICT,
    reason TEXT NOT NULL,
    llm_explanation TEXT,
    llm_label VARCHAR(20) 
        CHECK (llm_label IS NULL OR llm_label IN ('likely_ok', 'review', 'likely_fraud')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 8. Transfer Audit Logs Table
CREATE TABLE transfer_audit_logs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id BIGINT NOT NULL REFERENCES transfers(id) ON DELETE RESTRICT,
    from_status VARCHAR(20),
    to_status VARCHAR(20) NOT NULL,
    changed_by VARCHAR(100) NOT NULL,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 9. Scheduled Job Locks Table
CREATE TABLE job_locks (
    job_name VARCHAR(100) PRIMARY KEY,
    locked_at TIMESTAMPTZ,
    locked_by VARCHAR(100)
);

-- 10. Reconciliation Runs Table
CREATE TABLE reconciliation_runs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMPTZ,
    status VARCHAR(30) NOT NULL 
        CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED_WITH_DISCREPANCIES', 'ERROR')),
    total_debits BIGINT NOT NULL DEFAULT 0,
    total_credits BIGINT NOT NULL DEFAULT 0,
    transfers_checked BIGINT NOT NULL DEFAULT 0,
    accounts_checked BIGINT NOT NULL DEFAULT 0,
    discrepancy_count INTEGER NOT NULL DEFAULT 0
);

-- 11. Reconciliation Discrepancies Table
CREATE TABLE reconciliation_discrepancies (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES reconciliation_runs(id) ON DELETE CASCADE,
    check_name VARCHAR(100) NOT NULL,
    entity_id BIGINT,
    expected_value TEXT NOT NULL,
    actual_value TEXT NOT NULL,
    details TEXT,
    detected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ==============================================================================
-- Performance & Query Indexes
-- ==============================================================================

-- Fast statement query ordering by newest first
CREATE INDEX idx_ledger_entries_account_created ON ledger_entries(account_id, created_at DESC, id DESC);

-- Fast transfer lookups
CREATE INDEX idx_transfers_source ON transfers(source_account_id, created_at DESC);
CREATE INDEX idx_transfers_destination ON transfers(destination_account_id, created_at DESC);
CREATE INDEX idx_transfers_held ON transfers(status) WHERE status = 'HELD';
CREATE INDEX idx_transfers_created_at ON transfers(created_at DESC);

-- Risk flags lookup
CREATE INDEX idx_risk_flags_transfer ON risk_flags(transfer_id);

-- Accounts lookup by user
CREATE INDEX idx_accounts_user ON accounts(user_id);
