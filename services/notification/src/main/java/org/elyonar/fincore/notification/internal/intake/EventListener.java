package org.elyonar.fincore.notification.internal.intake;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The broker adapter, and deliberately nothing more.
 *
 * <p>Every decision lives in {@link EventIntake}, which is a method taking a publisher and a JSON
 * envelope. That split is what lets the whole contract — deduplication, epoch fencing, staleness,
 * policy, suppression — be tested against a real database without a broker, and it means swapping
 * Kafka for RabbitMQ is a class beside this one rather than a rewrite (ADR 0005).
 *
 * <p><strong>An exception must escape.</strong> The container commits an offset only after the
 * listener returns, so a Core outage that leaves an event unhandled must fail loudly here: the
 * offset stays put and the event is redelivered. Catching and logging would commit the offset and
 * lose the message permanently — the one failure this service must never have, because the
 * customer is simply never told.
 */
@Component
public class EventListener {

    private static final Logger log = LoggerFactory.getLogger(EventListener.class);

    /** The publisher's name, as it appears in the deduplication key. */
    private static final String CORE = "core";

    private final EventIntake intake;

    public EventListener(EventIntake intake) {
        this.intake = intake;
    }

    @KafkaListener(
            topics = "#{'${fincore.notification.topics:fincore.core.transfer.completed}'.split(',')}",
            groupId = "${spring.kafka.consumer.group-id:notification}")
    public void onCoreEvent(String envelope) {
        EventIntake.Disposition disposition = intake.handle(CORE, envelope);
        if (disposition == EventIntake.Disposition.SUPPRESSED) {
            // Not an error — a recorded decision. Logged at debug because the database is the
            // record and a busy tenant's quiet hours would otherwise fill a log with non-events.
            log.debug("event suppressed; see notification.suppressions");
        }
    }
}
