package org.elyonar.fincore.core.orchestration.internal.ledger;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.UUID;
import org.elyonar.fincore.core.orchestration.api.LedgerOutcome;

/**
 * Turns what we observed of a Ledger call into one of three outcomes.
 *
 * <p>This class is the outcome protocol. Everything else in the saga engine trusts it, so it is
 * written to be read: each branch states what was observed and why it maps where it does.
 *
 * <p>The subtle half is transport failures. A client library that raises one exception type for
 * "the connection was refused" (nothing was sent, so nothing happened) and for "the read timed out"
 * (the request was sent, so anything may have happened) destroys the distinction the whole protocol
 * rests on. So the classification is explicit about which is which, and the default for anything
 * unrecognised is {@code UNKNOWN} — the safe direction, because an unknown is retried while a
 * wrongly-assumed failure is compensated.
 */
public final class OutcomeClassifier {

    /** The Ledger's response to a reversal when someone else already reversed the target. */
    private static final String ALREADY_REVERSED = "ALREADY_REVERSED";

    /** Our key collided with a different payload. That is a defect here, not a business refusal. */
    private static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";

    private OutcomeClassifier() {}

    /**
     * Classifies a response we actually received and could read.
     *
     * @param status the HTTP status
     * @param errorCode the Ledger's error code from the body, or null on success
     * @param transactionId the transaction id from the body, when the status implies one
     */
    public static LedgerOutcome ofResponse(int status, String errorCode, UUID transactionId) {
        if (status >= 200 && status < 300) {
            if (transactionId == null) {
                // A 2xx we cannot tie to a transaction is not a success we can record. Treating it
                // as one would leave a COMPLETED saga with nothing to reconcile against.
                return new LedgerOutcome.Unknown("2xx without a transaction id");
            }
            return new LedgerOutcome.Success(transactionId);
        }

        if (status >= 400 && status < 500) {
            // A rejection is total: the Ledger's contract is that a rejected request leaves zero
            // rows, no balance change and no event. So a 4xx is terminal for this key.
            if (ALREADY_REVERSED.equals(errorCode)) {
                // Not a failure. Someone else's reversal won; the response carries its id and the
                // saga converges on it rather than retry-looping against a settled outcome.
                return transactionId == null
                        ? new LedgerOutcome.Unknown("ALREADY_REVERSED without the winner's id")
                        : new LedgerOutcome.Success(transactionId);
            }
            if (IDEMPOTENCY_KEY_REUSED.equals(errorCode)) {
                // Our derivation produced a collision with a different payload. Fail loudly:
                // minting a new key to route around it is exactly how a double post happens.
                return new LedgerOutcome.DefiniteFailure(IDEMPOTENCY_KEY_REUSED, true);
            }
            return LedgerOutcome.DefiniteFailure.of(
                    errorCode == null ? "HTTP_" + status : errorCode);
        }

        // 5xx, 1xx, 3xx: the Ledger may have committed before failing to answer, or answered
        // something we do not understand. Either way we do not know.
        return new LedgerOutcome.Unknown("HTTP " + status);
    }

    /**
     * Classifies a call that never produced a readable response.
     *
     * <p>The one case that is a definite failure is a connection that was refused or could not be
     * established: the request was never written, so the Ledger never saw it. Everything after the
     * bytes leave is unknown.
     */
    public static LedgerOutcome ofTransportFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {

            // Nothing was sent. Safe to call a definite failure — and worth doing, because a
            // ledger that is simply down should fail sagas fast rather than parking every one of
            // them in an ops queue.
            if (cause instanceof ConnectException
                    || cause instanceof UnknownHostException
                    || cause instanceof NoRouteToHostException) {
                return LedgerOutcome.DefiniteFailure.of("LEDGER_UNREACHABLE");
            }

            // The request was written and the answer never arrived — or arrived and we could not
            // read it. The Ledger may well have committed.
            if (cause instanceof SocketTimeoutException) {
                return new LedgerOutcome.Unknown("read timeout");
            }
            if (cause instanceof IOException) {
                return new LedgerOutcome.Unknown(
                        "connection failure after send: " + cause.getClass().getSimpleName());
            }
        }

        // Unrecognised. Unknown is the safe direction: it is retried against an idempotent API,
        // where a wrongly-assumed failure would be compensated.
        return new LedgerOutcome.Unknown(failure.getClass().getSimpleName());
    }
}
