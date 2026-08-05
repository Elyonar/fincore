package org.elyonar.fincore.ledger.outbox;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development adapter: logs each event and acknowledges it. <strong>It delivers nothing.</strong>
 *
 * <p>Active only when no broker is configured — {@link KafkaEventPublisher} takes over as soon as
 * {@code spring.kafka.bootstrap-servers} is set (ADR 0005). It exists so that a test run, CI and a
 * developer with no Kafka can still exercise the full relay contract, and so that the choice of
 * broker never reached the posting path.
 *
 * <p>Naming matters here: this is not a fallback that quietly half-works in production. A
 * deployment running on this adapter is emitting no events at all, and the startup summary says so.
 */
@Component
@ConditionalOnProperty(name = "ledger.events.broker", havingValue = "log", matchIfMissing = true)
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
