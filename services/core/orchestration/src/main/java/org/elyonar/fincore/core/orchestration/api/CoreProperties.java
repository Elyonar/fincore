package org.elyonar.fincore.core.orchestration.api;

/**
 * Core's configuration keys, named once.
 *
 * <p>A property key written as a literal in an annotation is invisible to the compiler: rename the
 * key in {@code application.yml} and the {@code @Value} that reads it keeps compiling and silently
 * takes its default. The failure surfaces in production as a worker that never runs or a Ledger URL
 * pointing at localhost, and nothing in the build objects.
 *
 * <p>Naming them here does not make the compiler check the YAML — nothing can — but it makes the
 * set of keys enumerable, greppable and changeable in one place, and it is what
 * {@code StartupSummary} reads so the banner cannot drift from what is actually bound.
 *
 * <p>The Ledger's equivalent is {@code LedgerProperties}.
 */
public final class CoreProperties {

    private CoreProperties() {}

    public static final String PREFIX = "fincore.core";

    /** Orchestration's only outbound dependency. No other module may hold a client. */
    public static final String LEDGER_BASE_URL = PREFIX + ".ledger.base-url";

    public static final String LEDGER_CONNECT_TIMEOUT_MS = PREFIX + ".ledger.connect-timeout-ms";

    /**
     * How long to wait for an answer before the outcome becomes Unknown.
     *
     * <p>Not a correctness knob: a shorter timeout does not make a posting fail, it makes Core stop
     * waiting and retry the same key. Set it too low and every posting becomes an unknown to be
     * resolved by the worker.
     */
    public static final String LEDGER_READ_TIMEOUT_MS = PREFIX + ".ledger.read-timeout-ms";

    public static final String WORKER_ID = PREFIX + ".worker.id";
    public static final String WORKER_INTERVAL_MS = PREFIX + ".worker.interval-ms";
    public static final String WORKER_LEASE_SECONDS = PREFIX + ".worker.lease-seconds";
    public static final String WORKER_ESCALATE_AFTER_ATTEMPTS = PREFIX + ".worker.escalate-after-attempts";
    public static final String WORKER_ESCALATE_AFTER_MINUTES = PREFIX + ".worker.escalate-after-minutes";

    public static final String OUTBOX_RELAY_INTERVAL_MS = PREFIX + ".outbox.relay.interval-ms";

    public static final String RECONCILIATION_ENABLED = PREFIX + ".reconciliation.enabled";
    public static final String RECONCILIATION_INTERVAL_MS = PREFIX + ".reconciliation.interval-ms";
    public static final String RECONCILIATION_LOOKBACK_HOURS = PREFIX + ".reconciliation.lookback-hours";

    /**
     * Spring bean names for the per-module datasources and their transaction managers.
     *
     * <p>These are wired by string — {@code @Qualifier("workerTransactionManager")} — so a typo is
     * a startup failure at best and, in the case of a transaction manager, a silent commit against
     * the wrong module's role at worst. The module boundary is enforced by database GRANTs
     * (ADR 0006); getting the qualifier wrong is how you route around it by accident.
     */
    public static final class Beans {

        private Beans() {}

        // Customer's and Product's names live in their own modules — CustomerBeans and
        // ProductBeans. Naming them here would make every module compile against Orchestration,
        // which is the cross-module dependency ADR 0006 exists to prevent.
        public static final String ORCHESTRATION_DATA_SOURCE = "orchestrationDataSource";
        public static final String ORCHESTRATION_JDBC = "orchestrationJdbcTemplate";
        public static final String ORCHESTRATION_TX = "orchestrationTransactionManager";

        public static final String RELAY_DATA_SOURCE = "relayDataSource";
        public static final String RELAY_JDBC = "relayJdbcTemplate";
        public static final String RELAY_TX = "relayTransactionManager";

        public static final String WORKER_DATA_SOURCE = "workerDataSource";
        public static final String WORKER_JDBC = "workerJdbcTemplate";
        public static final String WORKER_TX = "workerTransactionManager";
    }
}
