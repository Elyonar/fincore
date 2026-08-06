package org.elyonar.fincore.events;

import java.util.List;

/**
 * Hands events to whatever carries them off a service.
 *
 * <p>An interface rather than a broker client, so no service grows a dependency on one. A relay is
 * the only component that talks outward, it does so after the state change has already committed,
 * and nothing on a write path can be delayed or failed by a broker being down.
 *
 * <p><strong>The batch shape is load-bearing.</strong> Publishing returns the ids the broker
 * acknowledged rather than succeeding or throwing as a whole: a batch where the third send fails
 * must still mark the first two published, or a single unlucky event stalls everything behind it
 * forever. A single-event signature cannot express that, which is why this is the shape both
 * services converged on.
 */
public interface EventPublisher {

    /**
     * Publishes a batch and returns the ids the broker acknowledged.
     *
     * <p>Only acknowledged ids may be marked published, so an unacknowledged event stays pending
     * and is retried on the next poll. Delivery is at-least-once; consumers deduplicate on the
     * outbox id.
     *
     * <p>Implementations must not throw for a failed send — a throw loses the acknowledgements
     * already collected in the same batch.
     */
    List<Long> publish(List<DomainEvent> batch);

    /** Short name for a startup banner, e.g. {@code kafka}. */
    String name();

    /**
     * Whether this actually delivers anywhere.
     *
     * <p>Reported at startup so "we publish events" is a claim someone can check rather than
     * assume. The logging adapter answers false.
     */
    boolean delivers();
}
