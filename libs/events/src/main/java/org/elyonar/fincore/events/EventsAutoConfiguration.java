package org.elyonar.fincore.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Selects the backbone at runtime.
 *
 * <p>An operator changes broker with configuration rather than a rebuild, and no broker type
 * reaches any domain (ADR 0005). The logging adapter is the fallback rather than an error, so a
 * developer without a broker gets a working system — and the banner is what keeps that from
 * becoming a production surprise.
 */
@AutoConfiguration
public class EventsAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EventsAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = EventsProperties.BROKER, havingValue = EventsProperties.Broker.KAFKA)
    public EventPublisher kafkaEventPublisher(
            KafkaTemplate<String, String> kafka,
            @Value("${" + EventsProperties.TOPIC_PREFIX + ":fincore}") String topicPrefix) {
        return new KafkaEventPublisher(kafka, topicPrefix);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = EventsProperties.BROKER, havingValue = EventsProperties.Broker.RABBIT)
    public EventPublisher rabbitEventPublisher(
            RabbitTemplate rabbit,
            @Value("${" + EventsProperties.EXCHANGE + ":fincore}") String exchange) {
        return new RabbitEventPublisher(rabbit, exchange);
    }

    /**
     * Declares the exchange the relay publishes to.
     *
     * <p>Without it RabbitMQ <em>silently discards</em> a message sent to an exchange that does not
     * exist: the publish returns normally, the relay marks the row published, and the event is gone
     * with nothing reporting an error. That is the outbox's own failure mode arriving through the
     * broker instead of the database. Durable, so a broker restart does not take the topology.
     */
    @Bean
    @ConditionalOnProperty(name = EventsProperties.BROKER, havingValue = EventsProperties.Broker.RABBIT)
    public TopicExchange fincoreEventsExchange(
            @Value("${" + EventsProperties.EXCHANGE + ":fincore}") String exchange) {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    @ConditionalOnMissingBean
    public EventPublisher loggingEventPublisher() {
        return new LoggingEventPublisher();
    }

    /** Says at startup whether events actually go anywhere. */
    @Bean
    public InitializingBean eventsStartupBanner(EventPublisher publisher) {
        return () -> {
            if (publisher.delivers()) {
                log.info("events: publisher '{}' active", publisher.name());
            } else {
                log.warn(
                        "events: publisher '{}' DELIVERS NOTHING — events are written and relayed"
                                + " but reach no broker. Development only; set {}=kafka or rabbit.",
                        publisher.name(), EventsProperties.BROKER);
            }
        };
    }
}
