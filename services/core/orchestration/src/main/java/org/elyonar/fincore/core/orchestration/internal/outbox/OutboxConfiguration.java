package org.elyonar.fincore.core.orchestration.internal.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Wires the relay and says at startup whether events actually go anywhere.
 *
 * <p>The banner is not decoration. The ledger shipped a logging adapter as its default and had to
 * carry a README line explaining that it delivered nothing; a component that silently does its job
 * badly is worse than one that fails, because the system reports itself working.
 */
@Configuration
public class OutboxConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OutboxConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public EventPublisher eventPublisher() {
        // Kafka and RabbitMQ adapters arrive when a consumer exists. The seam is what matters now:
        // the backbone stays a deployment choice rather than a code dependency (ADR 0005, 0008).
        return new LoggingEventPublisher();
    }

    @Bean
    public InitializingBean outboxStartupBanner(EventPublisher publisher) {
        return () -> {
            if (publisher.delivers()) {
                log.info("core outbox: publisher '{}' active", publisher.name());
            } else {
                log.warn(
                        "core outbox: publisher '{}' DELIVERS NOTHING — events are written and"
                                + " relayed but reach no broker. Development only.",
                        publisher.name());
            }
        };
    }

    /** The relay pass. Separate bean so a test can drive the relay without a timer racing it. */
    @Bean
    public RelayScheduler relayScheduler(OutboxRelay relay) {
        return new RelayScheduler(relay);
    }

    /** Polls the outbox and alerts when it falls behind. */
    public static class RelayScheduler {

        private final OutboxRelay relay;

        RelayScheduler(OutboxRelay relay) {
            this.relay = relay;
        }

        @Scheduled(fixedDelayString = "${fincore.core.outbox.relay.interval-ms:1000}")
        public void relay() {
            try {
                relay.publishBatch(100);
                // > 60s pending means the relay is not keeping up, or has stopped.
                relay.warnIfStale(60);
            } catch (RuntimeException e) {
                LoggerFactory.getLogger(RelayScheduler.class).error("outbox relay pass failed", e);
            }
        }
    }
}
