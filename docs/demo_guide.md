# 5-Minute Demo Video Walkthrough Guide

This guide provides the exact curl commands and narrative steps to demonstrate all 5 core capabilities in a 5-minute demo video.

---

## 1. Setup & Starting the Services (0:00 - 0:45)
Run the application using Docker Compose or Gradle:
```bash
docker compose up -d
# Or locally:
./gradlew bootRun
```
Verify health:
```bash
curl -i http://localhost:8080/actuator/health
```

---

## 2. One Transfer End-to-End (0:45 - 1:45)
**Narrative:** *"Money moves only through double-entry ledger transfers with atomic row locks."*

1. Create test user & wallets:
```bash
# Create User Asha
curl -X POST http://localhost:8080/users \
  -H "Content-Type: application/json" \
  -d '{"name": "Asha Sharma", "email": "asha.demo@example.com", "phone": "+919876500001"}'

# Create User Rahul
curl -X POST http://localhost:8080/users \
  -H "Content-Type: application/json" \
  -d '{"name": "Rahul Verma", "email": "rahul.demo@example.com", "phone": "+919876500002"}'

# Create Wallets (Returns Wallet IDs, e.g. 2005 and 2006)
curl -X POST http://localhost:8080/wallets -H "Content-Type: application/json" -d '{"userId": 1001}'
curl -X POST http://localhost:8080/wallets -H "Content-Type: application/json" -d '{"userId": 1002}'

# Top-up Asha's wallet with ₹500 (50,000 paise)
curl -X POST "http://localhost:8080/wallets/2005/topup?amount=50000"
```

2. Execute transfer from Asha to Rahul for ₹150 (15,000 paise):
```bash
curl -i -X POST http://localhost:8080/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 11111111-2222-3333-4444-555555555555" \
  -d '{
    "sourceAccountId": 2005,
    "destinationAccountId": 2006,
    "amountPaise": 15000,
    "transferType": "PEER_TRANSFER",
    "note": "Lunch share"
  }'
```
Show response: `status: "COMPLETED"`, amount: 15000.
Show balances updated and statement:
```bash
curl http://localhost:8080/wallets/2005/statement?limit=5
```

---

## 3. One Retry (Safe Idempotency) (1:45 - 2:30)
**Narrative:** *"Clients can retry payments safely without double-charging."*

1. Replay the exact same request with the same `Idempotency-Key`:
```bash
curl -i -X POST http://localhost:8080/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 11111111-2222-3333-4444-555555555555" \
  -d '{
    "sourceAccountId": 2005,
    "destinationAccountId": 2006,
    "amountPaise": 15000,
    "transferType": "PEER_TRANSFER",
    "note": "Lunch share"
  }'
```
Show that the response is returned immediately from cache without creating a new transfer. Check balances to prove no extra money moved.

2. Replay with the same key but changed amount (e.g. ₹200):
```bash
curl -i -X POST http://localhost:8080/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 11111111-2222-3333-4444-555555555555" \
  -d '{
    "sourceAccountId": 2005,
    "destinationAccountId": 2006,
    "amountPaise": 20000,
    "transferType": "PEER_TRANSFER",
    "note": "Lunch share"
  }'
```
Show `HTTP 422 Unprocessable Entity` with `IDEMPOTENCY_CONFLICT`.

---

## 4. One Held Transfer & Admin Release (2:30 - 3:30)
**Narrative:** *"Transfers exceeding risk thresholds are held before money moves."*

1. Trigger the High-Value Transfer rule (threshold ₹10,000 / 1,000,000 paise):
```bash
# First top-up Asha with ₹50,000
curl -X POST "http://localhost:8080/wallets/2005/topup?amount=5000000"

# Attempt transfer of ₹15,000 (1,500,000 paise)
curl -i -X POST http://localhost:8080/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 22222222-3333-4444-5555-666666666666" \
  -d '{
    "sourceAccountId": 2005,
    "destinationAccountId": 2006,
    "amountPaise": 1500000,
    "transferType": "PEER_TRANSFER",
    "note": "High value payment"
  }'
```
Show response: `status: "HELD"`.
Show that Asha's balance was NOT debited. Money did not move!

2. Admin releases the held transfer:
```bash
curl -i -X POST http://localhost:8080/transfers/102002/release \
  -H "X-Admin-User: SecurityOfficer"
```
Show response: `status: "COMPLETED"`. Balance is now debited and credited.

---

## 5. One Reconciliation Run (3:30 - 4:15)
**Narrative:** *"Scheduled nightly audit runs in Repeatable Read to prove the books balance."*

Trigger manual reconciliation run:
```bash
curl -i -X POST http://localhost:8080/admin/reconciliation/run
```
Show response:
```json
{
  "runId": 1,
  "status": "SUCCESS",
  "totalDebits": 1255139500,
  "totalCredits": 1255139500,
  "transfersChecked": 102002,
  "accountsChecked": 2005,
  "discrepancyCount": 0
}
```

---

## 6. One Q&A Answer (4:15 - 5:00)
**Narrative:** *"Plain-English statement questions answered using safe parameterized SQL."*

Ask a statement question:
```bash
curl -i -X POST http://localhost:8080/users/1001/statement/ask \
  -H "Content-Type: application/json" \
  -d '{"question": "How much did I send to Rahul this month?"}'
```
Show response:
```json
{
  "question": "How much did I send to Rahul this month?",
  "questionType": 1,
  "answerSummary": "Total sent to Rahul between 2026-10-01 and 2026-10-24: ₹150.00 across 1 transfer(s)",
  "resultAmountPaise": 15000,
  "resultAmountRupees": 150.0,
  "resultCount": 1,
  "transferIds": [102001]
}
```
Explain that the LLM only translates text to structured JSON query parameters; SQL is strictly parameterized and limited to caller's accounts, preventing SQL and prompt injection attacks.
