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
}
