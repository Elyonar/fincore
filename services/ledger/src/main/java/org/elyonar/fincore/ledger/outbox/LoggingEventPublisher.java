package org.elyonar.fincore.ledger.outbox;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * The default publisher until a broker exists: logs the batch and acknowledges it.
 *
 * <p>Deliberately not a no-op that silently drops events, and deliberately not a broker client
 * bolted on early. The relay contract can be exercised and tested in full now, and the day a
 * broker arrives it replaces this one bean without touching the posting path.
 */
@Component
@ConditionalOnMissingBean(ignored = LoggingEventPublisher.class, value = EventPublisher.class)
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public List<Long> publish(List<PublishedEvent> batch) {
        for (PublishedEvent event : batch) {
            log.info("outbox → {} [{}] {}", event.eventType(), event.id(), event.payload());
        }
        return batch.stream().map(PublishedEvent::id).toList();
    }
}
