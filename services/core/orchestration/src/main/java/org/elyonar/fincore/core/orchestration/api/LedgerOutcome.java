package org.elyonar.fincore.core.orchestration.api;

import java.util.UUID;

/**
 * The result of asking the Ledger to do something — one of exactly three things.
 *
 * <p>Three, never two. {@code UNKNOWN} is not a kind of failure; it is the absence of an answer,
 * and the difference is what separates a correct ledger integration from one that loses or
 * duplicates money. See {@code services/core/docs/outcome-protocol.md}.
 *
 * <p>Sealed so that a {@code switch} over outcomes cannot silently omit a case. Adding a fourth
 * outcome would break every consumer at compile time, which is the correct amount of friction for a
 * change of that kind.
 */
public sealed interface LedgerOutcome {

    /** The operation provably happened. */
    record Success(UUID ledgerTransactionId) implements LedgerOutcome {
        public Success {
            if (ledgerTransactionId == null) {
                throw new IllegalArgumentException(
                        "a success must name the transaction it created — otherwise the saga has"
                                + " nothing to reconcile against");
            }
        }
    }

    /**
     * The operation provably did <em>not</em> happen.
     *
     * <p>The only outcome from which compensation is legal.
     *
     * @param errorCode the Ledger's error code, e.g. {@code INSUFFICIENT_FUNDS}
     * @param callerBug true when the rejection indicates a defect on our side rather than a
     *     legitimate business refusal — {@code IDEMPOTENCY_KEY_REUSED} means our key derivation
     *     collided, and retrying or minting a new key would turn a bug into a double post
     */
    record DefiniteFailure(String errorCode, boolean callerBug) implements LedgerOutcome {
        public static DefiniteFailure of(String errorCode) {
            return new DefiniteFailure(errorCode, false);
        }
    }

    /**
     * Not known, and possibly true.
     *
     * <p>Compensation is forbidden here. The saga retries the same derived idempotency key until
     * the Ledger gives a definitive answer, or escalates to an ops case — it never guesses.
     *
     * @param reason what was observed, for the attempt log. Redacted before storage.
     */
    record Unknown(String reason) implements LedgerOutcome {}

    /** Convenience for the one question most call sites ask. */
    default boolean isUnknown() {
        return this instanceof Unknown;
    }
}
