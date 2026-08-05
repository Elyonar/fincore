package org.elyonar.fincore.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Ledger Service — the crown jewel (PRD §4.1).
 *
 * <p>Owns: chart of accounts, postings, balances, idempotency registry, invariant checks.
 * Never in this service: fee logic, product rules, external calls, orchestration.
 * Keep it small, boring, rarely changed.
 *
 * <p>Design: docs/design.md — agreed before any domain code lands here.
 */
@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class LedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerApplication.class, args);
    }
}
