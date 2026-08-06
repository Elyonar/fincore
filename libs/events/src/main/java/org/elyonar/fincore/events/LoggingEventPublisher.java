package org.elyonar.fincore.events;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development adapter. <strong>Delivers nothing.</strong>
 *
 * <p>It acknowledges everything, so the relay marks rows published and the outbox does not grow
 * without bound on a developer's machine — which is exactly why it must be loud about delivering
 * nowhere. A component that silently does its job badly is worse than one that fails, because the
 * system reports itself working.
 */
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public List<Long> publish(List<DomainEvent> batch) {
        List<Long> acknowledged = new ArrayList<>(batch.size());
        for (DomainEvent event : batch) {
            log.info(
                    "event (not delivered anywhere): id={} type={} aggregate={}",
                    event.id(), event.eventType(), event.aggregateId());
            acknowledged.add(event.id());
        }
        return acknowledged;
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
