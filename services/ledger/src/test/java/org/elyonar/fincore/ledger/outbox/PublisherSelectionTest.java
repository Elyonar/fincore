package org.elyonar.fincore.ledger.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.elyonar.fincore.ledger.support.LedgerPostgresTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@DisplayName("publisher selection — exactly one, chosen by configuration")
class PublisherSelectionTest {

    @Nested
    @DisplayName("with no broker configured")
    class WithoutBroker extends LedgerPostgresTest {
        @Autowired EventPublisher publisher;

        @Test
        @DisplayName("the development adapter is used, and it delivers nothing")
        void logging_adapter_is_active() {
            assertThat(publisher).isInstanceOf(LoggingEventPublisher.class);
        }
    }

    @Nested
    @DisplayName("with ledger.events.broker=kafka")
    @TestPropertySource(
            properties = {"ledger.events.broker=kafka", "spring.kafka.bootstrap-servers=localhost:29092"})
    class WithKafka extends LedgerPostgresTest {
        @Autowired EventPublisher publisher;

        @Test
        @DisplayName("Kafka takes over without any code change")
        void kafka_publisher_is_active() {
            assertThat(publisher)
                    .as("one property is the whole switch; the posting path never sees it")
                    .isInstanceOf(KafkaEventPublisher.class);
        }
    }

    @Nested
    @DisplayName("with ledger.events.broker=rabbit")
    @TestPropertySource(properties = {"ledger.events.broker=rabbit", "spring.rabbitmq.host=localhost"})
    class WithRabbit extends LedgerPostgresTest {
        @Autowired EventPublisher publisher;

        @Test
        @DisplayName("RabbitMQ is a supported alternative, not a rewrite")
        void rabbit_publisher_is_active() {
            assertThat(publisher)
                    .as("the backbone is a deployment choice; no ledger code changes with it")
                    .isInstanceOf(RabbitEventPublisher.class);
        }
    }
}
