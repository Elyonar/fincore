package org.elyonar.fincore.notification.internal.intake;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * What happens when the listener throws — stated, not defaulted.
 *
 * <p>{@code EventListener}'s contract is that an escaping exception keeps the offset put and the
 * event is redelivered. Spring Kafka's <em>default</em> error handler honours that for only ten
 * attempts, then logs and advances — which silently loses the event during any Core outage longer
 * than a few seconds. That is the one failure this service must never have, so the default is
 * replaced with two explicit behaviours:
 *
 * <ul>
 *   <li><strong>Transient failures retry forever.</strong> Core unreachable, the database down, a
 *       template datasource hiccup — the offset stays put and the container retries until the
 *       dependency returns. Delivery is late; it is never silently never.
 *       <p>Backing off <em>exponentially</em>, and saying so. This retried once a second forever
 *       and logged nothing at any level: a service that had never successfully sent a message
 *       looked identical to one with nothing to send, while it queried a dead dependency 86,400
 *       times a day. Neither half of that was deliberate. The retry semantics are unchanged —
 *       forever is still forever, because losing a customer's alert is the one outcome this
 *       service refuses — but the interval grows to a minute and every attempt is on the record.
 *   <li><strong>A malformed envelope is skipped loudly.</strong> It can never succeed — the
 *       publisher broke the ADR 0008 contract — and retrying it forever would park the partition
 *       behind a poison message, which starves every well-formed event behind it. It is logged at
 *       ERROR with the raw payload's offset so the contract bug is findable, and the offset moves
 *       on. A dead-letter topic can replace the log when a gateway exists to alert on one.
 * </ul>
 */
@Configuration
public class KafkaErrorHandling {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandling.class);

    /** First retry, and how far apart they grow to. A minute is often enough for an outage to end. */
    private static final long FIRST_RETRY_MS = 1_000L;
    private static final long MAX_RETRY_MS = 60_000L;

    /**
     * Attempts before the log changes from "this is retrying" to "somebody needs to look".
     *
     * <p>With the backoff above, this is a few minutes in — long enough that a restart or a brief
     * outage has not tripped it, short enough that a genuinely stuck consumer is named while the
     * person who deployed it is still at their desk.
     */
    private static final int STUCK_AFTER_ATTEMPTS = 8;

    @Bean
    public DefaultErrorHandler kafkaErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(FIRST_RETRY_MS, 2.0);
        backOff.setMaxInterval(MAX_RETRY_MS);
        // No max elapsed time: retrying forever is the decision above, not an oversight here.

        DefaultErrorHandler handler = new DefaultErrorHandler(KafkaErrorHandling::recoverPoisonMessage, backOff);
        // The recoverer runs only for these; everything else retries until it stops throwing.
        handler.addNotRetryableExceptions(EventEnvelope.MalformedEnvelope.class);
        handler.setRetryListeners(KafkaErrorHandling::onFailedDelivery);
        return handler;
    }

    /**
     * Every failed attempt, on the record.
     *
     * <p>WARN while it still looks like weather, ERROR once it does not. The offset is included
     * because it is the only handle an operator has on the event that is stuck, and the cause's
     * message rather than its stack after the first attempt — a stack repeated every minute is how
     * a log stops being read.
     */
    private static void onFailedDelivery(ConsumerRecord<?, ?> record, Exception exception, int deliveryAttempt) {
        if (deliveryAttempt < STUCK_AFTER_ATTEMPTS) {
            log.warn(
                    "event delivery failed, will retry — topic={} partition={} offset={} attempt={} cause={}",
                    record.topic(), record.partition(), record.offset(), deliveryAttempt, describe(exception));
            return;
        }
        log.error(
                "event has failed {} times and is blocking this partition — nothing behind it is being"
                        + " notified. topic={} partition={} offset={} cause={}",
                deliveryAttempt, record.topic(), record.partition(), record.offset(), describe(exception));
    }

    private static String describe(Exception exception) {
        Throwable cause = exception.getCause() == null ? exception : exception.getCause();
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private static void recoverPoisonMessage(ConsumerRecord<?, ?> record, Exception exception) {
        // ERROR and loud, deliberately: this is a publisher contract bug, not an operational
        // hiccup, and the fix is a code change on the publishing side.
        log.error(
                "malformed envelope skipped — publisher contract bug (ADR 0008):"
                        + " topic={} partition={} offset={}",
                record.topic(),
                record.partition(),
                record.offset(),
                exception);
    }
}
