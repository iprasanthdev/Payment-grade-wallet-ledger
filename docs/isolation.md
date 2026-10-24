# Milestone 2: Transaction Isolation Levels & Concurrency Lab

This document records the exact commands, execution traces, and behavior of PostgreSQL 15+ under different transaction isolation levels: **Read Committed**, **Repeatable Read**, and **Serializable**, as well as an analysis of row-level locking strategies for payment-grade ledger safety.

---

## Lab Setup

A test user and wallet account were initialized in the database:
```sql
INSERT INTO users (name, email, phone, kyc_status) 
VALUES ('Asha Sharma', 'asha@example.com', '+919876543210', 'VERIFIED');

INSERT INTO accounts (user_id, account_type, currency, balance, status) 
VALUES (1, 'USER_MAIN', 'INR', 50000, 'ACTIVE'); -- Account ID = 4, ₹500.00
```

---

## Step 1: See Isolation Levels for Yourself

### Case 1: Read Committed (Postgres Default)
In Read Committed, each query inside a transaction sees a snapshot of committed data taken at the moment the query began.

| Time | Session A (Terminal 1) | Session B (Terminal 2) |
| :--- | :--- | :--- |
| $T_1$ | `BEGIN TRANSACTION ISOLATION LEVEL READ COMMITTED;` | |
| $T_2$ | `SELECT balance FROM accounts WHERE id = 4;` <br> **Output:** `50000` | |
| $T_3$ | | `BEGIN;` |
| $T_4$ | | `UPDATE accounts SET balance = balance + 1000 WHERE id = 4;` <br> `COMMIT;` |
| $T_5$ | `SELECT balance FROM accounts WHERE id = 4;` <br> **Output:** `51000` | |
| $T_6$ | `COMMIT;` | |

**Observation:**
Session A saw a different value (`50000` $\to$ `51000`) within the same transaction! This demonstrates a **non-repeatable read**. Read Committed alone is not sufficient if code reads a balance, performs logic in memory, and assumes the balance has not changed without a lock.

---

### Case 2: Repeatable Read
In Repeatable Read, the transaction takes a single snapshot at the start of its first query. All subsequent queries see only data committed before that snapshot.

| Time | Session A (Terminal 1) | Session B (Terminal 2) |
| :--- | :--- | :--- |
| $T_1$ | `BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ;` | |
| $T_2$ | `SELECT balance FROM accounts WHERE id = 4;` <br> **Output:** `51000` | |
| $T_3$ | | `BEGIN;` |
| $T_4$ | | `UPDATE accounts SET balance = balance + 1000 WHERE id = 4;` <br> `COMMIT;` |
| $T_5$ | `SELECT balance FROM accounts WHERE id = 4;` <br> **Output:** `51000` | |
| $T_6$ | `COMMIT;` | |

**Observation:**
Session A still sees `51000` even though Session B committed `52000`. The snapshot remains stable across the entire transaction.

---

### Case 3: Serializable
Serializable guarantees that any concurrent execution of transactions produces the exact same outcome as some serial (one-by-one) execution. PostgreSQL achieves this using SSI (Serializable Snapshot Isolation).

| Time | Session A (Terminal 1) | Session B (Terminal 2) |
| :--- | :--- | :--- |
| $T_1$ | `BEGIN TRANSACTION ISOLATION LEVEL SERIALIZABLE;` | `BEGIN TRANSACTION ISOLATION LEVEL SERIALIZABLE;` |
| $T_2$ | `SELECT balance FROM accounts WHERE id = 4;` <br> **Output:** `52000` | |
| $T_3$ | | `SELECT balance FROM accounts WHERE id = 4;` <br> **Output:** `52000` |
| $T_4$ | `UPDATE accounts SET balance = balance - 100 WHERE id = 4;` <br> `COMMIT;` <br> **Output:** `COMMIT` | |
| $T_5$ | | `UPDATE accounts SET balance = balance - 100 WHERE id = 4;` <br> `COMMIT;` <br> **Output:** `ERROR: could not serialize access due to concurrent update` |

**PostgreSQL Console Error in Session B:**
```
ERROR:  could not serialize access due to concurrent update
SQLSTATE: 40001
```

**Observation:**
PostgreSQL detected that Session B's write depended on a snapshot that was invalidated by Session A's commit. Session B is aborted with a serialization failure (`40001`). The application is responsible for rolling back and retrying.

---

### Case 4: Repeatable Read with a Join
We test whether Repeatable Read's snapshot isolation holds when joining `accounts` with `users`.

| Time | Session A (Terminal 1) | Session B (Terminal 2) |
| :--- | :--- | :--- |
| $T_1$ | `BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ;` | |
| $T_2$ | `SELECT a.id, a.balance, u.name FROM accounts a JOIN users u ON a.user_id = u.id WHERE a.id = 4;` <br> **Output:** `4 \| 51900 \| Asha Sharma` | |
| $T_3$ | | `BEGIN;` <br> `UPDATE accounts SET balance = balance + 500 WHERE id = 4;` <br> `COMMIT;` |
| $T_4$ | `SELECT a.id, a.balance, u.name FROM accounts a JOIN users u ON a.user_id = u.id WHERE a.id = 4;` <br> **Output:** `4 \| 51900 \| Asha Sharma` | |
| $T_5$ | `COMMIT;` | |

**Observation:**
The snapshot guarantee holds identically across table joins. No phantom or updated rows bleed through the join.

---

## Step 2: "Break It First" Demonstration

If a transfer is written naively without row-level locks:
```
1. Read balance: SELECT balance FROM accounts WHERE id = ?
2. Check in code: if (balance < transferAmount) throw InsufficientFunds;
3. Update balance: UPDATE accounts SET balance = balance - transferAmount WHERE id = ?
```

### What happens under concurrent stampede?
Suppose Account 4 has ₹500 (50,000 paise). 100 threads concurrently fire ₹10 requests:
- Thread 1 and Thread 2 both read balance = 50,000 paise simultaneously.
- Both pass the in-memory check (`50000 >= 1000`).
- Both execute `UPDATE accounts SET balance = 49000`. One update overwrites the other (lost update), or if `balance = balance - 1000` is used without locks, threads read stale balances and deduct beyond available funds, causing an **overdraft** (or hitting our DB check constraint and failing unexpectedly).

We demonstrate this explicitly in `UnsafeTransferTest` to measure the lost updates and overdraft attempts before applying row locks.

---

## Step 3: Fix It with Row Locks (`SELECT ... FOR UPDATE`)

To make transfers 100% safe under load:
1. **Sorted Row Locking to Prevent Deadlocks:**
   If Transfer 1 moves money from Account 10 $\to$ 20, while Transfer 2 moves money from Account 20 $\to$ 10:
   - Without ordering: Transfer 1 locks 10 then waits for 20. Transfer 2 locks 20 then waits for 10 $\to$ **DEADLOCK!**
   - **With ascending ordering:** Both transfers lock `min(from, to)` first, then `max(from, to)`.
     ```sql
     SELECT id, balance, status 
     FROM accounts 
     WHERE id IN (?, ?) 
     ORDER BY id ASC 
     FOR UPDATE;
     ```
   Deadlock becomes mathematically impossible because all transactions acquire locks in the exact same global order.
2. **Re-read Balance While Holding the Lock:**
   Because the row is locked with an exclusive write lock, the balance returned is the true, current, committed value.
3. **Validate Balance & Insert Balanced Double-Entry Lines:**
   If balance is insufficient, abort immediately before moving money.
   If sufficient, insert transfer record, write debit and credit rows to `ledger_entries`, and update `accounts.balance`.
4. **Commit:**
   Releases the locks atomically. If any error occurs, the transaction rolls back cleanly.

---

## Comparison: Read Committed + Row Locks vs. Serializable

| Aspect | Read Committed + `FOR UPDATE` | Serializable (SSI) |
| :--- | :--- | :--- |
| **Mechanic** | Pessimistic exclusive locking on affected account rows. | Optimistic execution with SIREAD dependency tracking. |
| **Deadlock Risk** | Zero (eliminated by sorting account IDs in ascending order). | Zero (concurrency conflicts resolved by aborting). |
| **Throughput under Contention** | **High & Predictable.** Threads queue on the row lock and execute sequentially. | **Low & Spiky.** High contention causes frequent `40001 serialization_failure` aborts. |
| **Application Complexity** | Low. Clean sequential execution; no retry loops needed for normal transfers. | High. Requires exponential backoff and retry middleware for aborted transactions. |
| **Database Overhead** | Low. Standard row-level lock table in shared memory. | Higher. Postgres must track SIREAD locks in `predicate.c` memory structures. |

### Conclusion
For our transfer execution path, **Read Committed with deterministic `ORDER BY id ASC FOR UPDATE` row locks** is the gold standard used by financial and payment backends (Stripe, UPI switches). It provides 100% consistency without the abort-and-retry penalty of Serializable. 

For the **Milestone 4 Nightly Reconciliation Job**, we run under **`REPEATABLE READ`**, which provides a point-in-time consistent view across all tables without locking the entire database.
