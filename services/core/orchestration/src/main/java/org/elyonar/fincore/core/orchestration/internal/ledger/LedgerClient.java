package org.elyonar.fincore.core.orchestration.internal.ledger;

import java.util.UUID;
import org.elyonar.fincore.core.orchestration.api.LedgerOutcome;
import org.elyonar.fincore.core.orchestration.api.LedgerPosting;
import org.elyonar.fincore.core.orchestration.api.LedgerRead;

/**
 * What Core may ask the Ledger to do.
 *
 * <p>Every method returns a {@link LedgerOutcome} and none throws for a failed call. That is the
 * point: a client that threw on 5xx and returned on 4xx would invite a caller to treat "rejected"
 * and "unknown" as the same thing via one catch block, which is the mistake the whole outcome
 * protocol exists to prevent.
 */
public interface LedgerClient {

    /**
     * Records a balanced transaction. Principal and fee are entries of the same posting.
     *
     * <p>The tenant is a parameter rather than something the posting carries, because it is not
     * part of the money movement — it is who the movement belongs to, and the Ledger scopes every
     * query by it. Omitting it is not a validation error the Ledger reports helpfully; it is a
     * request that resolves to no tenant and therefore to nothing.
     */
    LedgerOutcome post(UUID tenantId, LedgerPosting posting);

    /** Reverses a previously posted transaction, under its own derived key. */
    LedgerOutcome reverse(UUID tenantId, UUID ledgerTransactionId, String idempotencyKey, String initiatedBy);

    /**
     * Reads a transaction back — non-mutating, for reconciliation and recovery. Never throws;
     * "could not ask" is an answer ({@link LedgerRead.Unknown}), not an exception.
     */
    LedgerRead read(UUID tenantId, UUID ledgerTransactionId);

    /**
     * A shape-preserving read for the surfaces Core proxies to clients (ADR 0014): statements and
     * account balances. The ledger's response travels back byte-for-byte — its statement contract
     * (period-bounded, {@code opening + Σ movements = closing}, final vs interim) is the product
     * feature, and Core must not blur it. Status 0 means "could not ask".
     */
    RawRead get(UUID tenantId, String pathAndQuery);

    /**
     * Opens an account, idempotently on the caller's key.
     *
     * <p>The one write on this interface that is not a posting, and it is here for the reason
     * hard rule 3 gives: orchestration is the only module that may address the ledger. Until it
     * existed nothing on the platform could create an account at all — every feature that names a
     * ledger account demanded a UUID that only a direct call to the ledger's own port could
     * produce, which meant an institution could be provisioned, staffed, and unable to take a
     * deposit.
     *
     * <p>Unlike {@link #post}, this returns a result rather than a {@link LedgerOutcome}: opening
     * an account is not money movement, so the three-way settled/refused/unknown protocol the saga
     * engine depends on would be ceremony here. A failure is reported plainly and the caller
     * retries with the same key, which returns the original account rather than a second one.
     */
    Opened open(UUID tenantId, OpenAccount request);

    /**
     * @param idempotencyKey unique per tenant; a retry returns the original account
     * @param type one of the ledger's account types — CUSTOMER, INTERNAL, FEE, SUSPENSE,
     *     AGENT_FLOAT, SETTLEMENT_MIRROR
     * @param customerRef an opaque reference, never PII: the ledger stores no names
     * @param allowNegative whether the balance may go below zero
     */
    record OpenAccount(
            String idempotencyKey,
            String type,
            String currency,
            String customerRef,
            boolean allowNegative) {}

    /**
     * @param accountId null when the account was not opened
     * @param failure null on success; a short reason otherwise, for the refusal a caller renders
     */
    record Opened(UUID accountId, String failure) {
        public boolean ok() {
            return accountId != null;
        }

        public static Opened of(UUID accountId) {
            return new Opened(accountId, null);
        }

        public static Opened failed(String failure) {
            return new Opened(null, failure);
        }
    }

    record RawRead(int status, String body) {
        public boolean unreachable() {
            return status == 0;
        }
    }
}
