package com.airtribe.wallet.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class SeedDataRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDataRunner.class);
    private final SeedService seedService;

    public SeedDataRunner(SeedService seedService) {
        this.seedService = seedService;
    }

    @Override
    public void run(String... args) {
        boolean shouldSeed = Arrays.stream(args).anyMatch(arg -> arg.contains("--seed"));
        if (shouldSeed) {
            log.info("Detected --seed command-line argument. Running seed script (1,000 users, 2,000 wallets, 100,000 transfers)...");
            seedService.runSeed(1000, 2000, 100000);
            log.info("Seed script completed successfully.");
        }
    }
}
