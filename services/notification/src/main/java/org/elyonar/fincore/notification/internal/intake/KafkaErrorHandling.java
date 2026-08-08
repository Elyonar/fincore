package org.elyonar.fincore.notification.internal.intake;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

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
 *       template datasource hiccup — the offset stays put and the container retries with a fixed
 *       backoff until the dependency returns. Delivery is late; it is never silently never.
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

    /** Retry interval for transient failures. Modest on purpose: outages end, offsets wait. */
    private static final long RETRY_INTERVAL_MS = 1_000L;

    @Bean
    public DefaultErrorHandler kafkaErrorHandler() {
        DefaultErrorHandler handler =
                new DefaultErrorHandler(
                        KafkaErrorHandling::recoverPoisonMessage,
                        new FixedBackOff(RETRY_INTERVAL_MS, FixedBackOff.UNLIMITED_ATTEMPTS));
        // The recoverer runs only for these; everything else retries until it stops throwing.
        handler.addNotRetryableExceptions(EventEnvelope.MalformedEnvelope.class);
        return handler;
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
