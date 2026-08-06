package org.elyonar.fincore.events;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Publishes to RabbitMQ (ADR 0005).
 *
 * <p><strong>Publisher confirms are mandatory here, not a tuning option.</strong> Without them
 * {@code convertAndSend} returns normally even when the broker discards the message — an unrouted
 * message, or an exchange that does not exist — and the relay would mark the row published having
 * delivered nothing. {@code waitForConfirms} is what makes "acknowledged" mean the broker actually
 * took it.
 */
public class RabbitEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitEventPublisher.class);

    /** How long to wait for the broker's confirm before treating a send as unacknowledged. */
    private static final long CONFIRM_TIMEOUT_MS = 5_000L;

    private final RabbitTemplate rabbit;
    private final String exchange;

    public RabbitEventPublisher(RabbitTemplate rabbit, String exchange) {
        this.rabbit = rabbit;
        this.exchange = exchange;
    }

    @Override
    public List<Long> publish(List<DomainEvent> batch) {
        List<Long> acknowledged = new ArrayList<>(batch.size());
        // Mandatory returns: an unroutable message comes back rather than vanishing.
        rabbit.setMandatory(true);
        for (DomainEvent event : batch) {
            try {
                Boolean confirmed =
                        rabbit.invoke(
                                operations -> {
                                    operations.convertAndSend(
                                            exchange,
                                            event.eventType(),
                                            event.envelope(),
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
                    log.warn("broker did not confirm event {} ({}); will retry", event.id(), event.eventType());
                    continue;
                }
                // Counted only once the broker has confirmed. invoke() runs the send on a channel
                // with confirms enabled, so this is the broker's answer, not the client's.
                acknowledged.add(event.id());
            } catch (RuntimeException e) {
                log.warn("broker did not accept event {} ({}); will retry", event.id(), event.eventType(), e);
            }
        }
        return acknowledged;
    }

    @Override
    public String name() {
        return "rabbit";
    }

    @Override
    public boolean delivers() {
        return true;
    }
}
