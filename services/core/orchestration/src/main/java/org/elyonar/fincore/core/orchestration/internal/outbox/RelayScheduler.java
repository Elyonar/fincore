package org.elyonar.fincore.core.orchestration.internal.outbox;

import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the relay.
 *
 * <p>Separate from {@link OutboxRelay} so a test can publish a batch deterministically without a
 * timer racing its assertions.
 */
@Component
public class RelayScheduler {

    private final OutboxRelay relay;

    public RelayScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    // Initial delay = interval, like every other job here: a scheduler that fires at t=0 runs a
    // pass during every context boot, which in a shared test database is a concurrent publisher
    // nobody asked for — and in production is a pass racing an instance that has not finished
    // starting. The worker and reconciliation jobs both follow this pattern already.
    @Scheduled(
            initialDelayString = "${fincore.core.outbox.relay.interval-ms:1000}",
            fixedDelayString = "${fincore.core.outbox.relay.interval-ms:1000}")
    public void relay() {
        try {
            relay.publishBatch(100);
            // Over a minute pending means the relay is behind, or stopped. A dead relay is
            // otherwise invisible until a consumer is noticed to have gone quiet.
            relay.warnIfStale(60);
        } catch (RuntimeException e) {
            LoggerFactory.getLogger(RelayScheduler.class).error("outbox relay pass failed", e);
        }
    }
}
