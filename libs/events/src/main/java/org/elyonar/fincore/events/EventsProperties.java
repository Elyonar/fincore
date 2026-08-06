package org.elyonar.fincore.events;

/**
 * The configuration keys this library reads, in one place.
 *
 * <p>They appear in {@code @ConditionalOnProperty} and {@code @Value} — annotation arguments, which
 * must be compile-time constants — so a key spelled differently in one of them fails silently
 * rather than loudly: the bean simply never loads, and the first symptom is behaviour quietly
 * absent. Referencing constants makes a rename one edit with the compiler finding the rest.
 *
 * <p>The prefix is deliberately platform-wide rather than per service. Two services publishing to
 * different backbones is a configuration mistake nobody would choose, and one key means an operator
 * cannot half-migrate by forgetting the other.
 */
public final class EventsProperties {

    private EventsProperties() {}

    /** Which backbone the relay publishes to: {@code kafka}, {@code rabbit} or {@code log}. */
    public static final String BROKER = "fincore.events.broker";

    /** Topic prefix for Kafka, e.g. {@code fincore.ledger} → {@code fincore.ledger.posting.completed}. */
    public static final String TOPIC_PREFIX = "fincore.events.topic-prefix";

    /** Exchange name for RabbitMQ. */
    public static final String EXCHANGE = "fincore.events.exchange";

    /** Values {@link #BROKER} accepts. */
    public static final class Broker {
        private Broker() {}

        public static final String KAFKA = "kafka";
        public static final String RABBIT = "rabbit";
        public static final String LOG = "log";
    }
}
