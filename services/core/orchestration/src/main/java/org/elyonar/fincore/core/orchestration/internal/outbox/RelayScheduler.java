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

    @Scheduled(fixedDelayString = "${fincore.core.outbox.relay.interval-ms:1000}")
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
