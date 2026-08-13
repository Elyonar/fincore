package org.elyonar.fincore.product.api;

import java.util.UUID;

/**
 * What Product needs to know about a ledger account, without being allowed to ask.
 *
 * <p>Pricing names accounts: a fee rule names where its fee income lands. Accepting
 * those unverified is the defect {@code V4__fee_account_configuration.sql} was written about —
 * <em>"a caller could route the tenant's fee to any account it could name"</em> — and configuration
 * is exactly where it should be caught, because the alternative is discovering it when a customer's
 * fee lands in somebody's savings.
 *
 * <p>Verifying means asking the ledger, and Product may not: {@code ModuleBoundaryTest} forbids it
 * holding an HTTP client (AGENTS.md hard rule 3), and the module graph runs Orchestration →
 * Product, never the reverse. So the port is declared here, in the module that needs the answer,
 * and implemented in Orchestration, which already holds the only ledger client on the platform.
 * Product gains no dependency; Orchestration gains no new one, since it already consumes
 * {@code product.api}. The seam is the same one {@code CustomerEligibility} uses in the other
 * direction.
 *
 * <p>This is a <strong>read</strong>. Opening an account is a separate capability that does not
 * exist yet (<code>admin-surface.md</code> §4), and nothing here creates anything.
 */
public interface LedgerAccounts {

    /**
     * Describes one account, or says why it could not.
     *
     * <p>Three answers, never two, for the reason {@code LedgerOutcome} gives: "absent" and "we
     * could not ask" are different facts, and collapsing them would let a ledger outage read as a
     * misconfigured account and refuse a version the operator had authored correctly.
     */
    sealed interface Account {

        /** The account exists and this is what it is. */
        record Known(String type, String currency, String status) implements Account {
            public boolean isOpen() {
                return "OPEN".equals(status);
            }
        }

        /** No such account in this tenant. Another tenant's is deliberately indistinguishable. */
        record Absent() implements Account {}

        /** The ledger could not be asked. Never treat this as absent. */
        record Unreadable(String reason) implements Account {}
    }

    /**
     * Reads one account.
     *
     * <p>Never throws for a business reason: an unknown account is {@link Account.Absent} and an
     * unreachable ledger is {@link Account.Unreadable}, so the caller decides what each means.
     */
    Account describe(UUID tenantId, UUID accountId);
}
