package org.elyonar.fincore.ledger.outbox;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Publishes outbox events to RabbitMQ.
 *
 * <p>Selected by {@code ledger.events.broker=rabbit}. Kafka is the platform's recommended backbone
 * (ADR 0005) because Compliance and Reporting need to replay history, and a queue drained by its
 * first reader cannot offer that. This adapter exists because that reasoning is about the
 * platform's consumers, not about the ledger — an operator whose consumers only react to what is
 * happening now should not be made to run Kafka to satisfy an argument that does not apply to them.
 *
 * <p>The trade-off is real and worth stating where the code is: with RabbitMQ, an event acknowledged
 * is an event gone. A consumer added later sees nothing that happened before it existed, and
 * rebuilding that history means reading the ledger's own tables — which is the coupling the event
 * backbone exists to avoid.
 *
 * <p>As with Kafka, only acknowledged sends are reported published, so a failed publish stays
 * pending and is retried on the next poll. The routing key is the event type; the aggregate id
 * travels as a message header so consumers can order or partition by it.
 *
 * <p><strong>Publisher confirms are mandatory here, not a tuning option.</strong> Without them
 * {@code convertAndSend} returns normally even when the broker discards the message — an unrouted
 * message, or an exchange that does not exist — and the relay would mark the row published having
 * delivered nothing. {@code waitForConfirms} is what makes "acknowledged" mean the broker actually
 * took it.
 */
@Component
@ConditionalOnProperty(name = "ledger.events.broker", havingValue = "rabbit")
public class RabbitEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitEventPublisher.class);

    /** How long to wait for the broker's confirm before treating a send as unacknowledged. */
    private static final long CONFIRM_TIMEOUT_MS = 5_000L;

    private final RabbitTemplate rabbit;
    private final String exchange;

    public RabbitEventPublisher(
            RabbitTemplate rabbit, @Value("${ledger.events.exchange:fincore.ledger}") String exchange) {
        this.rabbit = rabbit;
        this.exchange = exchange;
    }

    @Override
    public List<Long> publish(List<PublishedEvent> batch) {
        List<Long> acknowledged = new ArrayList<>(batch.size());
        // Mandatory returns: an unroutable message comes back rather than vanishing.
        rabbit.setMandatory(true);
        for (PublishedEvent event : batch) {
            try {
                Boolean confirmed =
                        rabbit.invoke(
                                operations -> {
                                    operations.convertAndSend(
                                            exchange,
                                            event.eventType(),
                                            event.payload(),
                                            message -> {
                                                var props = message.getMessageProperties();
                                                props.setHeader("aggregateId", event.aggregateId());
                                                props.setHeader("outboxId", event.id());
                                                props.setContentType("application/json");
                                                return message;
                                            });
                                    return operations.waitForConfirms(CONFIRM_TIMEOUT_MS);
                                });
                if (!Boolean.TRUE.equals(confirmed)) {
                    log.warn("broker did not confirm outbox event {} ({}); will retry",
                            event.id(), event.eventType());
                    continue;
                }
                // Only count it once the broker has confirmed. invoke() runs the send on a
                // channel with confirms enabled, so this is the broker's answer, not the client's.
                acknowledged.add(event.id());
            } catch (RuntimeException e) {
                log.warn("broker did not accept outbox event {} ({}); will retry",
                        event.id(), event.eventType(), e);
            }
        }
        return acknowledged;
    }
}
