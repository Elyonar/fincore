package org.elyonar.fincore.ledger.hold;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.elyonar.fincore.ledger.shared.LedgerProperties;

/**
 * Runs the hold expiry sweep on a timer.
 *
 * <p>Only the trigger is conditional. The sweep itself is always a bean, so switching scheduling
 * off in tests silences the timer without removing the code under test.
 */
@Component
@ConditionalOnProperty(name = LedgerProperties.HOLDS_EXPIRY_ENABLED, havingValue = "true", matchIfMissing = true)
public class HoldExpiryScheduler {

    private final HoldExpirySweep sweep;

    public HoldExpiryScheduler(HoldExpirySweep sweep) {
        this.sweep = sweep;
    }

    @Scheduled(fixedDelayString = "${" + LedgerProperties.HOLDS_EXPIRY_INTERVAL_MS + ":30000}")
    public void run() {
        sweep.sweep();
    }
}
