package org.elyonar.fincore.ledger.shared;

/**
 * Every configuration key this service reads, in one place.
 *
 * <p>These appear in {@code @ConditionalOnProperty}, {@code @Value}, {@code @Scheduled} and the
 * startup summary — annotation arguments, which must be compile-time constants, so a key spelled
 * differently in one of them fails silently rather than loudly. A typo in
 * {@code @ConditionalOnProperty} does not error; the bean simply never loads, and the first symptom
 * is behaviour quietly absent.
 *
 * <p>Referencing constants means a rename is one edit and the compiler finds the rest.
 */
public final class LedgerProperties {

    private LedgerProperties() {}

    /** Which event backbone the relay publishes to: {@code kafka}, {@code rabbit} or {@code log}. */
    public static final String EVENTS_BROKER = "ledger.events.broker";

    public static final String EVENTS_TOPIC_PREFIX = "ledger.events.topic-prefix";
    public static final String EVENTS_EXCHANGE = "ledger.events.exchange";

    public static final String OUTBOX_RELAY_ENABLED = "ledger.outbox.relay.enabled";
    public static final String OUTBOX_RELAY_INTERVAL_MS = "ledger.outbox.relay.interval-ms";
    public static final String OUTBOX_RELAY_BATCH_SIZE = "ledger.outbox.relay.batch-size";
    public static final String OUTBOX_PURGE_CRON = "ledger.outbox.purge.cron";

    public static final String HOLDS_EXPIRY_ENABLED = "ledger.holds.expiry.enabled";
    public static final String HOLDS_EXPIRY_INTERVAL_MS = "ledger.holds.expiry.interval-ms";

    public static final String INVARIANTS_ENABLED = "ledger.invariants.enabled";
    public static final String INVARIANTS_ANCHOR_CRON = "ledger.invariants.anchor-cron";
    public static final String INVARIANTS_VERIFY_INTERVAL_MS = "ledger.invariants.verify-interval-ms";
    public static final String INVARIANTS_FULL_CRON = "ledger.invariants.full-cron";

    /** Broker values {@link #EVENTS_BROKER} accepts. */
    public static final class Broker {
        private Broker() {}

        public static final String KAFKA = "kafka";
        public static final String RABBIT = "rabbit";

        /** Development adapter. Delivers nothing; the startup summary says so. */
        public static final String LOG = "log";
    }
}
