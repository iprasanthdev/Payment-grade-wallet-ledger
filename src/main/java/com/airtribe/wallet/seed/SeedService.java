package com.airtribe.wallet.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Service
public class SeedService {

    private static final Logger log = LoggerFactory.getLogger(SeedService.class);
    private final JdbcTemplate jdbcTemplate;

    public SeedService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void runSeed(int numUsers, int numWallets, int numTransfers) {
        long startTime = System.currentTimeMillis();
        log.info("Starting seed process: {} users, {} wallets, {} transfers...", numUsers, numWallets, numTransfers);

        // 1. Seed Users in Batch
        log.info("Seeding {} users...", numUsers);
        String insertUserSql = "INSERT INTO users (name, email, phone, kyc_status) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING";
        List<Object[]> userBatch = new ArrayList<>(numUsers);
        for (int i = 1; i <= numUsers; i++) {
            userBatch.add(new Object[]{
                    "User " + i,
                    "seeduser" + i + "@example.com",
                    String.format("+9191%08d", i),
                    "VERIFIED"
            });
        }
        jdbcTemplate.batchUpdate(insertUserSql, userBatch);

        // Fetch user IDs
        List<Long> userIds = jdbcTemplate.query(
                "SELECT id FROM users WHERE email LIKE 'seeduser%' ORDER BY id ASC LIMIT ?",
                (rs, rowNum) -> rs.getLong("id"),
                numUsers
        );
        if (userIds.isEmpty()) {
            throw new IllegalStateException("No users found to seed wallets");
        }

        // 2. Seed Wallets in Batch (initial balance = 0, will be funded via initial top-ups)
        log.info("Seeding {} wallets...", numWallets);
        String insertAccountSql = "INSERT INTO accounts (user_id, account_type, currency, balance, status) VALUES (?, ?, 'INR', 0, 'ACTIVE')";
        List<Object[]> walletBatch = new ArrayList<>(numWallets);

        int walletsPerUser = Math.max(1, numWallets / userIds.size());
        for (Long uid : userIds) {
            walletBatch.add(new Object[]{uid, "USER_MAIN"});
            if (walletsPerUser > 1) {
                walletBatch.add(new Object[]{uid, "USER_SAVINGS"});
            }
        }
        jdbcTemplate.batchUpdate(insertAccountSql, walletBatch);

        // Fetch wallet IDs (excluding system accounts 1, 2, 3)
        List<Long> walletIds = jdbcTemplate.query(
                "SELECT id FROM accounts WHERE user_id IS NOT NULL ORDER BY id ASC",
                (rs, rowNum) -> rs.getLong("id")
        );

        int totalWallets = walletIds.size();
        log.info("Total user wallets active: {}", totalWallets);

        long currentTransferId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM transfers", Long.class);
        long currentEntryId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM ledger_entries", Long.class);

        Instant baseTime = Instant.now().minusSeconds(86400 * 30L); // 30 days ago
        long initialTopUpPaise = 500000L; // ₹5,000 per wallet

        String insertTransferSql = """
            INSERT INTO transfers (id, transfer_type, status, amount, source_account_id, destination_account_id, note, created_at, updated_at)
            OVERRIDING SYSTEM VALUE
            VALUES (?, ?, 'COMPLETED', ?, ?, ?, ?, ?, ?)
            """;

        String insertLedgerSql = """
            INSERT INTO ledger_entries (id, transfer_id, account_id, direction, amount, created_at)
            OVERRIDING SYSTEM VALUE
            VALUES (?, ?, ?, ?, ?, ?)
            """;

        // 3. Seed Initial Top-Up Transfers from SYSTEM_CASH_IN (Account 1) to each wallet
        log.info("Seeding initial top-up transfers from SYSTEM_CASH_IN (Account 1) for all {} wallets...", totalWallets);
        List<Object[]> topUpTransfers = new ArrayList<>(totalWallets);
        List<Object[]> topUpLedger = new ArrayList<>(totalWallets * 2);

        for (Long wId : walletIds) {
            currentTransferId++;
            Timestamp ts = Timestamp.from(baseTime);

            topUpTransfers.add(new Object[]{
                    currentTransferId,
                    "TOP_UP",
                    initialTopUpPaise,
                    1L, // System Cash-In
                    wId,
                    "Initial Top-up",
                    ts,
                    ts
            });

            currentEntryId++;
            topUpLedger.add(new Object[]{currentEntryId, currentTransferId, 1L, "DEBIT", initialTopUpPaise, ts});
            currentEntryId++;
            topUpLedger.add(new Object[]{currentEntryId, currentTransferId, wId, "CREDIT", initialTopUpPaise, ts});
        }

        jdbcTemplate.batchUpdate(insertTransferSql, topUpTransfers);
        jdbcTemplate.batchUpdate(insertLedgerSql, topUpLedger);

        // Update stored balance on Cash-In and user accounts
        jdbcTemplate.update("UPDATE accounts SET balance = balance - ? WHERE id = 1", initialTopUpPaise * totalWallets);
        jdbcTemplate.update("UPDATE accounts SET balance = ? WHERE user_id IS NOT NULL", initialTopUpPaise);

        // 4. Seed Peer Transfers & Balanced Ledger Entries in Batch
        log.info("Seeding {} peer transfers...", numTransfers);
        Random random = new Random(42);

        int batchSize = 5000;
        int totalBatches = (numTransfers + batchSize - 1) / batchSize;

        for (int b = 0; b < totalBatches; b++) {
            int currentBatchCount = Math.min(batchSize, numTransfers - (b * batchSize));
            List<Object[]> transferRows = new ArrayList<>(currentBatchCount);
            List<Object[]> ledgerRows = new ArrayList<>(currentBatchCount * 2);

            for (int i = 0; i < currentBatchCount; i++) {
                currentTransferId++;
                int srcIdx = random.nextInt(totalWallets);
                int dstIdx = random.nextInt(totalWallets);
                while (dstIdx == srcIdx) {
                    dstIdx = random.nextInt(totalWallets);
                }

                Long srcId = walletIds.get(srcIdx);
                Long dstId = walletIds.get(dstIdx);
                long amountPaise = (random.nextInt(50) + 1) * 100L; // ₹1 to ₹50
                Instant timestamp = baseTime.plusSeconds((b * batchSize + i) * 20L);
                Timestamp sqlTs = Timestamp.from(timestamp);

                transferRows.add(new Object[]{
                        currentTransferId,
                        "PEER_TRANSFER",
                        amountPaise,
                        srcId,
                        dstId,
                        "Batch Seed Transfer #" + currentTransferId,
                        sqlTs,
                        sqlTs
                });

                currentEntryId++;
                ledgerRows.add(new Object[]{currentEntryId, currentTransferId, srcId, "DEBIT", amountPaise, sqlTs});
                currentEntryId++;
                ledgerRows.add(new Object[]{currentEntryId, currentTransferId, dstId, "CREDIT", amountPaise, sqlTs});
            }

            jdbcTemplate.batchUpdate(insertTransferSql, transferRows);
            jdbcTemplate.batchUpdate(insertLedgerSql, ledgerRows);

            if ((b + 1) % 5 == 0 || b == totalBatches - 1) {
                log.info("Seeded {} / {} transfers...", Math.min((b + 1) * batchSize, numTransfers), numTransfers);
            }
        }

        // 5. Synchronize stored account balances with all ledger entries
        log.info("Synchronizing stored account balances from computed ledger entries...");
        String syncBalancesSql = """
            UPDATE accounts a
            SET balance = COALESCE((
                SELECT SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END)
                FROM ledger_entries
                WHERE account_id = a.id
            ), 0)
            """;
        jdbcTemplate.update(syncBalancesSql);

        // Update database sequences to max ID
        jdbcTemplate.execute("SELECT setval(pg_get_serial_sequence('transfers', 'id'), COALESCE((SELECT MAX(id) FROM transfers), 1));");
        jdbcTemplate.execute("SELECT setval(pg_get_serial_sequence('ledger_entries', 'id'), COALESCE((SELECT MAX(id) FROM ledger_entries), 1));");

        long duration = System.currentTimeMillis() - startTime;
        log.info("SUCCESS: Seeded {} users, {} wallets, and {} transfers in {} ms ({} seconds)!",
                numUsers, totalWallets, numTransfers, duration, duration / 1000.0);
    }
}
