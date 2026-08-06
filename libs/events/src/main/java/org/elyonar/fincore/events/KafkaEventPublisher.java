package org.elyonar.fincore.events;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * Publishes to Kafka (ADR 0005).
 *
 * <p>A broker outage delays delivery rather than failing a write, because the state change has
 * already committed by the time a relay runs.
 *
 * <p>Two decisions carry weight:
 *
 * <ul>
 *   <li><strong>Only acknowledged ids are returned.</strong> The relay marks published exactly what
 *       this reports, so a send that failed or timed out stays pending and is retried. Reporting an
 *       unacknowledged send as delivered would lose the event silently — the failure an outbox
 *       exists to prevent.
 *   <li><strong>The key is the aggregate id.</strong> Kafka orders within a partition, so keying on
 *       the aggregate gives ordered delivery per account or transaction, which is the only ordering
 *       ever promised. It implies no global order, and consumers stay state-based.
 * </ul>
 *
 * <p>Sends are issued for the whole batch first and awaited afterwards, so the batch costs one
 * round trip rather than one per event.
 */
public class KafkaEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final String topicPrefix;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafka, String topicPrefix) {
        this.kafka = kafka;
        this.topicPrefix = topicPrefix;
    }

    @Override
    public List<Long> publish(List<DomainEvent> batch) {
        List<CompletableFuture<SendResult<String, String>>> sends = new ArrayList<>(batch.size());
        for (DomainEvent event : batch) {
            sends.add(kafka.send(topicPrefix + "." + event.eventType(), event.aggregateId(), event.payload()));
        }

        List<Long> acknowledged = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            DomainEvent event = batch.get(i);
            try {
                sends.get(i).join();
                acknowledged.add(event.id());
            } catch (RuntimeException e) {
                // Left pending on purpose. The next poll picks it up; at-least-once with
                // consumer-side dedupe on the outbox id is the contract.
                log.warn("broker did not acknowledge event {} ({}); will retry",
                        event.id(), event.eventType(), e);
            }
        }
        return acknowledged;
    }

    @Override
    public String name() {
        return "kafka";
    }

    @Override
    public boolean delivers() {
        return true;
    }
}
