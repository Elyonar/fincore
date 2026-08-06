package org.elyonar.fincore.core.orchestration.internal.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development adapter. <strong>Delivers nothing.</strong>
 *
 * <p>Kept honest about it: {@link #delivers()} returns false and the startup banner says so. The
 * ledger shipped this exact shape and its README had to carry a line explaining that the default
 * adapter delivered nothing — a component that silently does its job badly is worse than one that
 * fails, because the system reports itself working.
 */
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(PendingEvent event) {
        log.info(
                "event (not delivered anywhere): id={} type={} aggregate={} tenant={} epoch={}",
                event.id(), event.eventType(), event.aggregateId(), event.tenantId(), event.epoch());
    }

    @Override
    public String name() {
        return "log";
    }

    @Override
    public boolean delivers() {
        return false;
    }
}
