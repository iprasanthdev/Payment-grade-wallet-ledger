-- ==============================================================================
-- Payment-Grade Wallet Ledger
-- V2: Seed System Accounts, Risk Rules, and Job Locks
-- ==============================================================================

-- Seed Platform System Accounts (Belong to no user, balance can be negative)
-- Account 1: SYSTEM_CASH_IN (for top-ups)
-- Account 2: SYSTEM_CASH_OUT (for withdrawals)
-- Account 3: SYSTEM_FEE_REVENUE (for platform fees)
INSERT INTO accounts (user_id, account_type, currency, balance, status)
VALUES 
    (NULL, 'SYSTEM_CASH_IN', 'INR', 0, 'ACTIVE'),
    (NULL, 'SYSTEM_CASH_OUT', 'INR', 0, 'ACTIVE'),
    (NULL, 'SYSTEM_FEE_REVENUE', 'INR', 0, 'ACTIVE');

-- Seed Standard Risk Rules
INSERT INTO risk_rules (rule_name, rule_type, threshold_value, action, description, is_active)
VALUES 
    ('HIGH_VALUE_TRANSFER', 'SINGLE_TRANSACTION_LIMIT', 1000000, 'HOLD', 'Transfers above ₹10,000 are held for human review', TRUE),
    ('RAPID_VELOCITY_LIMIT', 'VELOCITY_1_MIN', 5, 'FLAG', 'More than 5 transfers within 60 seconds is flagged for review', TRUE);

-- Seed Job Locks
INSERT INTO job_locks (job_name, locked_at, locked_by)
VALUES 
    ('RECONCILIATION_JOB', NULL, NULL),
    ('LLM_EXPLANATION_JOB', NULL, NULL);
