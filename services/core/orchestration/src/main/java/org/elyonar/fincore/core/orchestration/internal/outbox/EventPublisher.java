package org.elyonar.fincore.core.orchestration.internal.outbox;

/**
 * Where relayed events go.
 *
 * <p>A seam, so the backbone stays a deployment choice rather than a code dependency — the same
 * shape ADR 0005 settled for the ledger. No broker type reaches the domain, and changing broker
 * requires no rebuild.
 *
 * <p>A publisher must throw rather than swallow: the relay marks a row published only after this
 * returns, so a silent failure would mark an event delivered that never left.
 */
public interface EventPublisher {

    void publish(PendingEvent event);

    /** Short name for the startup banner. */
    String name();

    /** Whether this actually delivers anywhere. Reported at startup so nobody has to assume. */
    boolean delivers();
}
