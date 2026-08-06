package org.elyonar.fincore.auth;

import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * The identity context for the request being served on this thread.
 *
 * <p>Scoped rather than merely set: {@link #runIn} always clears on exit, including on exception.
 * A context that outlives its request is the authorization equivalent of the pooled-connection
 * tenant bleed the ledger guards against — the next piece of work on this thread would inherit the
 * previous caller's identity, and every check would faithfully pass for the wrong person.
 *
 * <p>The previous value is restored rather than blanked, so nesting is safe.
 */
public final class IdentityContextHolder {

    private static final ThreadLocal<IdentityContext> CURRENT = new ThreadLocal<>();

    private IdentityContextHolder() {}

    /** The current context, or empty when the thread is serving nothing authenticated. */
    public static Optional<IdentityContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * The current context, or a denial.
     *
     * @throws NotAuthenticatedException when there is none — deny by default (PRD §6.5 rule 3)
     */
    public static IdentityContext require() {
        IdentityContext context = CURRENT.get();
        if (context == null) {
            throw new NotAuthenticatedException("no identity context on this thread");
        }
        return context;
    }

    /**
     * Runs {@code work} with {@code context} in scope, restoring whatever was there before.
     *
     * <p>Named differently from {@link #runIn} rather than overloading it. Overloads on
     * {@code Runnable} and {@code Callable} are ambiguous for any lambda whose body is an
     * expression: Java resolves it to {@code Callable}, and the caller is then forced to handle a
     * checked {@code Exception} it never intended to throw. One name per shape costs nothing and
     * removes the papercut from every service that imports this.
     */
    public static <T> T callIn(IdentityContext context, Callable<T> work) throws Exception {
        IdentityContext previous = CURRENT.get();
        CURRENT.set(context);
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

    /** Runs {@code work} with {@code context} in scope, restoring whatever was there before. */
    public static void runIn(IdentityContext context, Runnable work) {
        IdentityContext previous = CURRENT.get();
        CURRENT.set(context);
        try {
            work.run();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
