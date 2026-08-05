package org.elyonar.fincore.ledger.outbox;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the exchange the relay publishes to.
 *
 * <p>Without this, RabbitMQ <em>silently discards</em> a message sent to an exchange that does not
 * exist: the publish call returns normally, the relay marks the row published, and the event is
 * gone with nothing anywhere reporting an error. That is precisely the failure the outbox exists to
 * prevent, arriving through the broker instead of the database.
 *
 * <p>Durable, because a broker restart must not take the topology with it.
 */
@Configuration
@ConditionalOnProperty(name = "ledger.events.broker", havingValue = "rabbit")
public class RabbitTopology {

    @Bean
    public TopicExchange ledgerEventsExchange(
            @org.springframework.beans.factory.annotation.Value("${ledger.events.exchange:fincore.ledger}")
                    String exchange) {
        return new TopicExchange(exchange, true, false);
    }
}
