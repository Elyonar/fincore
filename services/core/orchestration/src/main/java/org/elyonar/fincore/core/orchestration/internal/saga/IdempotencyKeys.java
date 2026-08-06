package org.elyonar.fincore.core.orchestration.internal.saga;

import java.util.UUID;
import org.elyonar.fincore.core.orchestration.api.CoreException;
import org.elyonar.fincore.core.orchestration.api.DetailKey;
import org.elyonar.fincore.core.orchestration.api.ErrorCode;
import org.elyonar.fincore.core.orchestration.api.ErrorReason;

/**
 * The idempotency key Core presents to the Ledger.
 *
 * <p><strong>A pure function of {@code (sagaId, step)}. Never random, never time-derived.</strong>
 *
 * <p>This is not a stylistic preference. The Ledger's contract binds its caller to retry the
 * <em>same</em> key when an outcome is unknown, and to never mint a new key for one. A key that
 * varied per attempt would make that contract unsatisfiable: the retry would post a second
 * transaction under a fresh key, and the Ledger's idempotency registry — which exists precisely to
 * prevent that — would have no way to recognise it. The double post would be created by the very
 * mechanism written to prevent it.
 *
 * <p>Derivable rather than stored, so it survives everything: a Core restart, a crash between
 * phases, a lease expiring and another instance picking the saga up, even a database restore. Any
 * worker holding the saga id can reconstruct the exact key the previous attempt used.
 */
public final class IdempotencyKeys {

    /** Namespace, so a key is legible in the Ledger's registry and cannot collide with a channel's. */
    private static final String PREFIX = "core";

    /** The Ledger caps keys at 200 characters. */
    private static final int MAX_LENGTH = 200;

    private IdempotencyKeys() {}

    /**
     * @param sagaId the saga this attempt belongs to
     * @param step which step of it — a saga with several outbound calls needs a distinct key per
     *     step, or the second would replay the first's result
     */
    public static String forStep(UUID sagaId, String step) {
        if (sagaId == null) {
            throw CoreException.of(ErrorCode.COMMAND_INVALID, ErrorReason.FIELD_REQUIRED)
                    .with(DetailKey.FIELD, "sagaId")
                    .message("sagaId is required");
        }
        if (step == null || step.isBlank()) {
            throw CoreException.of(ErrorCode.COMMAND_INVALID, ErrorReason.FIELD_REQUIRED)
                    .with(DetailKey.FIELD, "step")
                    .message("step is required");
        }
        if (step.indexOf(':') >= 0) {
            // A colon in the step would make the key ambiguous to parse and, worse, would let two
            // different (saga, step) pairs produce one key.
            throw CoreException.of(ErrorCode.COMMAND_INVALID, ErrorReason.STEP_CONTAINS_SEPARATOR)
                    .with(DetailKey.FIELD, "step")
                    .message("step must not contain ':'");
        }
        String key = PREFIX + ":" + sagaId + ":" + step;
        if (key.length() > MAX_LENGTH) {
            throw CoreException.of(ErrorCode.COMMAND_INVALID, ErrorReason.DERIVED_KEY_TOO_LONG)
                    .with(DetailKey.MAX_LENGTH, 200)
                    .message("derived key exceeds the Ledger's 200-character cap");
        }
        return key;
    }
}
