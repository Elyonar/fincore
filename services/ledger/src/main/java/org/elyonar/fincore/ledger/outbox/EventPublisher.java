package org.elyonar.fincore.ledger.outbox;

import java.util.List;

/**
 * Hands events to whatever carries them off this service.
 *
 * <p>An interface rather than a broker client, because the ledger must not grow a dependency on
 * one. The relay is the only component that talks outward, it does so after the money has already
 * committed, and nothing in the posting path can be delayed or failed by a broker being down.
 */
public interface EventPublisher {

    /**
     * Publishes a batch, returning the ids the broker acknowledged.
     *
     * <p>Only acknowledged ids are marked published, so an unacknowledged event is retried on the
     * next poll. Delivery is at-least-once and consumers deduplicate on the outbox id.
     */
    List<Long> publish(List<PublishedEvent> batch);
}
