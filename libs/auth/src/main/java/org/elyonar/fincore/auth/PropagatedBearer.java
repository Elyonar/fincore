package org.elyonar.fincore.auth;

import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * The inbound bearer token, held for the duration of the request so an outbound call can carry the
 * originating principal onward — the "outbound propagation" ADR 0009 designed and ADR 0014
 * schedules.
 *
 * <p>Scoped exactly like {@link IdentityContextHolder}: set by the identity filter, cleared on
 * exit including on exception, previous value restored so nesting is safe. A token that outlives
 * its request would be attached to the next caller's outbound calls — the propagation twin of the
 * pooled-thread identity bleed the context holder guards against.
 *
 * <p>Only a <em>verifying</em> resolver populates this. Dev-mode headers assert identity without
 * evidence, and forwarding an assertion would launder it into something a downstream service might
 * mistake for a credential.
 */
public final class PropagatedBearer {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private PropagatedBearer() {}

    /** The raw bearer token of the request being served, or empty when there is none. */
    public static Optional<String> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Runs {@code work} with {@code bearer} in scope, restoring whatever was there before. */
    public static <T> T callWith(String bearer, Callable<T> work) throws Exception {
        String previous = CURRENT.get();
        CURRENT.set(bearer);
        try {
            return work.call();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
