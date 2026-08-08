package org.elyonar.fincore.core.orchestration.internal.reconcile;

import org.elyonar.fincore.core.orchestration.api.CoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs reconciliation on a fixed delay, hourly by default.
 *
 * <p>A separate class so the pass itself stays callable from a test (and, one day, an endpoint)
 * without a scheduler attached — the same split every other scheduled job here uses. Never
 * propagates: a reconciliation crash must not kill the scheduling thread that would run the next
 * one, and a pass that failed is a pass the next tick retries in full, because the job is
 * idempotent by construction.
 */
@Component
@ConditionalOnProperty(name = CoreProperties.RECONCILIATION_ENABLED, havingValue = "true", matchIfMissing = true)
public class ReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final Reconciliation reconciliation;

    public ReconciliationScheduler(Reconciliation reconciliation) {
        this.reconciliation = reconciliation;
    }

    // The first pass waits one interval rather than firing at startup: a booting instance's
    // ledger client may not be reachable yet, and every test context would otherwise run a
    // reconciliation against whatever stub it happened to be pointing at.
    @Scheduled(
            initialDelayString = "${" + CoreProperties.RECONCILIATION_INTERVAL_MS + ":3600000}",
            fixedDelayString = "${" + CoreProperties.RECONCILIATION_INTERVAL_MS + ":3600000}")
    public void tick() {
        try {
            reconciliation.run();
        } catch (RuntimeException e) {
            log.error("reconciliation pass failed; the next tick retries in full", e);
        }
    }
}
