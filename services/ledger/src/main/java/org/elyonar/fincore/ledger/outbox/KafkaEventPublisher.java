package org.elyonar.fincore.ledger.outbox;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * Publishes outbox events to Kafka (ADR 0005).
 *
 * <p>Selected by {@code ledger.events.broker=kafka}. A broker outage delays delivery rather than
 * failing a posting, because the money has already committed by the time this runs.
 *
 * <p>Two decisions carry weight here:
 *
 * <ul>
 *   <li><strong>Only acknowledged ids are returned.</strong> The relay marks published exactly what
 *       this reports, so a send that failed or timed out stays pending and is retried on the next
 *       poll. Reporting an unacknowledged send as delivered would lose the event silently, which is
 *       the failure the outbox exists to prevent.
 *   <li><strong>The key is the aggregate id.</strong> Kafka orders within a partition, so keying on
 *       the aggregate gives ordered delivery per account or transaction — which is the only
 *       ordering the relay ever promised. It deliberately does not imply a global order, and
 *       consumers must remain state-based.
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "ledger.events.broker", havingValue = "kafka")
public class KafkaEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final String topicPrefix;

    public KafkaEventPublisher(
            KafkaTemplate<String, String> kafka,
            @Value("${ledger.events.topic-prefix:fincore.ledger}") String topicPrefix) {
        this.kafka = kafka;
        this.topicPrefix = topicPrefix;
    }

    @Override
    public List<Long> publish(List<PublishedEvent> batch) {
        List<CompletableFuture<SendResult<String, String>>> sends = new ArrayList<>(batch.size());
        for (PublishedEvent event : batch) {
            sends.add(kafka.send(topicFor(event), event.aggregateId(), event.payload()));
        }

        List<Long> acknowledged = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            PublishedEvent event = batch.get(i);
            try {
                sends.get(i).join();
                acknowledged.add(event.id());
            } catch (RuntimeException e) {
                // Left pending on purpose. The next poll picks it up; at-least-once with
                // consumer-side dedupe on the outbox id is the contract.
                log.warn("broker did not acknowledge outbox event {} ({}); will retry",
                        event.id(), event.eventType(), e);
            }
        }
        return acknowledged;
    }

    /** One topic per event type, e.g. {@code fincore.ledger.posting.completed}. */
    private String topicFor(PublishedEvent event) {
        return topicPrefix + "." + event.eventType();
    }
}
