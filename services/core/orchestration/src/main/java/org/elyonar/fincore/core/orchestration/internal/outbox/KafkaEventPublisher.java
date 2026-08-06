package org.elyonar.fincore.core.orchestration.internal.outbox;

import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Publishes Core's outbox events to Kafka (ADR 0005, ADR 0008).
 *
 * <p>Selected by {@code fincore.core.events.broker=kafka}. A broker outage delays delivery; it
 * never fails a transfer, because the money has already committed by the time the relay runs.
 *
 * <p><strong>The send is awaited, and a failure throws.</strong> The relay marks a row published
 * only after this returns, so swallowing a failed send would mark an event delivered that never
 * left — the exact silent loss the outbox exists to prevent.
 *
 * <p><strong>The key is the aggregate id.</strong> Kafka orders within a partition, so keying on
 * the aggregate gives ordered delivery per transaction — the only ordering this platform ever
 * promised. It implies nothing global, and consumers stay state-based.
 */
public class KafkaEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final String topicPrefix;
    private final long sendTimeoutSeconds;

    public KafkaEventPublisher(
            KafkaTemplate<String, String> kafka,
            @Value("${fincore.core.events.topic-prefix:fincore.core}") String topicPrefix,
            @Value("${fincore.core.events.send-timeout-seconds:10}") long sendTimeoutSeconds) {
        this.kafka = kafka;
        this.topicPrefix = topicPrefix;
        this.sendTimeoutSeconds = sendTimeoutSeconds;
    }

    @Override
    public void publish(PendingEvent event) {
        // One topic per event type, so a consumer subscribes to what it cares about rather than
        // filtering a firehose: fincore.core.transfer.completed, and so on.
        String topic = topicPrefix + "." + event.eventType();
        try {
            kafka.send(topic, event.aggregateId(), envelope(event)).get(sendTimeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted publishing event " + event.id(), e);
        } catch (Exception e) {
            // Left unpublished, so the next poll retries it. At-least-once is the contract.
            throw new IllegalStateException("failed to publish event " + event.id() + " to " + topic, e);
        }
        log.debug("published event {} to {}", event.id(), topic);
    }

    /**
     * The platform envelope (ADR 0008) wrapped around the module's payload.
     *
     * <p>Assembled here rather than stored, so the envelope's shape is one decision in one place
     * and the outbox row stays the module's own business.
     */
    private String envelope(PendingEvent event) {
        return """
               {"eventId":%d,"eventType":"%s","aggregateId":"%s","tenantId":"%s","epoch":%d,"payload":%s}"""
                .formatted(
                        event.id(),
                        event.eventType(),
                        event.aggregateId(),
                        event.tenantId(),
                        event.epoch(),
                        event.payload());
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
