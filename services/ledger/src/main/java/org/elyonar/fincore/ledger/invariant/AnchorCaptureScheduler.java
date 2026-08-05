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

    public AnchorCaptureScheduler(AnchorService anchors) {
        this.anchors = anchors;
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
