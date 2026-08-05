package org.elyonar.fincore.ledger.invariant;

import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Captures anchors once a day, and verifies incrementally against them hourly.
 *
 * <p>Only the timers live here; the work is in {@link AnchorService} so that switching scheduling
 * off does not remove the code under test.
 */
@Component
@ConditionalOnProperty(name = "ledger.invariants.enabled", havingValue = "true", matchIfMissing = true)
public class AnchorCaptureScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnchorCaptureScheduler.class);

    private final AnchorService anchors;
    private final InvariantService invariants;

    public AnchorCaptureScheduler(AnchorService anchors, InvariantService invariants) {
        this.anchors = anchors;
        this.invariants = invariants;
    }

    /** Early morning, after the business day has settled in the tenant's zone. */
    @Scheduled(cron = "${ledger.invariants.anchor-cron:0 15 2 * * *}")
    public void captureAnchors() {
        LocalDate today = LocalDate.now();
        for (UUID tenantId : anchors.tenantsWithAccounts()) {
            try {
                int written = anchors.captureFor(tenantId, today);
                log.info("captured {} balance anchors for tenant {}", written, tenantId);
            } catch (RuntimeException e) {
                // One tenant's failure must not stop the others being anchored.
                log.error("anchor capture failed for tenant {}", tenantId, e);
            }
        }
    }

    /**
     * The weekly full-history proof.
     *
     * <p>The hourly check verifies anchor-plus-delta, which is only as trustworthy as the anchors.
     * This one re-derives everything from the entries themselves, so an anchor that was wrong when
     * written cannot hide behind checks that keep trusting it.
     *
     * <p>Scheduled for a quiet hour. Pointing it at a read replica is a deployment concern
     * (`ledger.invariants.full-datasource`), and until one is configured it runs against the
     * primary — which is acceptable at current volumes and explicitly not acceptable at seven
     * years of history. That threshold is recorded in testing.md rather than left to be
     * discovered.
     */
    @Scheduled(cron = "${ledger.invariants.full-cron:0 45 3 * * SUN}")
    public void verifyFully() {
        for (UUID tenantId : anchors.tenantsWithAccounts()) {
            try {
                var report = invariants.verifyFull(tenantId);
                if (!report.clean()) {
                    log.error("full verification found {} violations for tenant {}",
                            report.violations(), tenantId);
                } else {
                    log.info("full verification clean for tenant {} ({} authorized exposures)",
                            tenantId, report.exposures());
                }
            } catch (RuntimeException e) {
                log.error("full verification failed for tenant {}", tenantId, e);
            }
        }
    }

    @Scheduled(fixedDelayString = "${ledger.invariants.verify-interval-ms:3600000}")
    public void verifyIncrementally() {
        for (UUID tenantId : anchors.tenantsWithAccounts()) {
            try {
                var findings = anchors.verifyIncrementally(tenantId);
                if (!findings.isEmpty()) {
                    // A violation here means a balance has drifted from its own entries. That is
                    // always a bug, never routine, which is why it is allowed to be loud.
                    log.error("invariant violations for tenant {}: {}", tenantId, findings);
                }
            } catch (RuntimeException e) {
                log.error("incremental verification failed for tenant {}", tenantId, e);
            }
        }
    }
}
