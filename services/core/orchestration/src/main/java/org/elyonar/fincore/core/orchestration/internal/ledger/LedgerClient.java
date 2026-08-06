package org.elyonar.fincore.core.orchestration.internal.ledger;

import java.util.UUID;
import org.elyonar.fincore.core.orchestration.api.LedgerOutcome;
import org.elyonar.fincore.core.orchestration.api.LedgerPosting;

/**
 * What Core may ask the Ledger to do.
 *
 * <p>Every method returns a {@link LedgerOutcome} and none throws for a failed call. That is the
 * point: a client that threw on 5xx and returned on 4xx would invite a caller to treat "rejected"
 * and "unknown" as the same thing via one catch block, which is the mistake the whole outcome
 * protocol exists to prevent.
 */
public interface LedgerClient {

    /** Records a balanced transaction. Principal and fee are entries of the same posting. */
    LedgerOutcome post(LedgerPosting posting);

    /** Reverses a previously posted transaction, under its own derived key. */
    LedgerOutcome reverse(UUID ledgerTransactionId, String idempotencyKey, String initiatedBy);
}
