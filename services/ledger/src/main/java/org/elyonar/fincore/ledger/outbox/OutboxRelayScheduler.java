package org.elyonar.fincore.ledger.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.elyonar.fincore.ledger.shared.LedgerProperties;

/**
 * Drains the outbox on a timer, and reports when it stops draining.
 *
 * <p>Disabled by default in tests, where relaying is driven explicitly so assertions are not
 * racing a background thread.
 */
@Component
@ConditionalOnProperty(name = LedgerProperties.OUTBOX_RELAY_ENABLED, havingValue = "true", matchIfMissing = true)
public class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    /**
     * Above this, the relay is presumed down: a dead relay is otherwise entirely silent.
     *
     * <p>Whole seconds, and a {@code long}: the no-floating-point rule is enforced across the
     * whole service rather than only on money, and an absolute rule needs no judgement at review
     * time. Nothing here wants sub-second precision anyway.
     */
    private static final long STALENESS_ALERT_SECONDS = 60L;

    private final OutboxRelay relay;
    private final int batchSize;

    public OutboxRelayScheduler(OutboxRelay relay, @Value("${" + LedgerProperties.OUTBOX_RELAY_BATCH_SIZE + ":100}") int batchSize) {
        this.relay = relay;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${" + LedgerProperties.OUTBOX_RELAY_INTERVAL_MS + ":1000}")
    public void drain() {
        try {
            while (relay.relayBatch(batchSize) > 0) {
                // Keep going while there is a backlog; the poll is O(pending) via the partial index.
            }
            relay.oldestPendingAgeSeconds()
                    .filter(age -> age > STALENESS_ALERT_SECONDS)
                    .ifPresent(age -> log.error("outbox relay is stale: oldest unpublished event is {}s old", age));
        } catch (RuntimeException e) {
            // Never let a relay failure escape into the scheduler and stop future runs. Money has
            // already committed; delivery is what is delayed.
            log.error("outbox relay pass failed; will retry on the next tick", e);
        }
    }

    /** Published rows are a delivery queue, not an archive: entries are the seven-year record. */
    @Scheduled(cron = "${" + LedgerProperties.OUTBOX_PURGE_CRON + ":0 30 3 * * *}")
    public void purge() {
        int deleted = relay.purgePublishedOlderThanDays(30);
        if (deleted > 0) {
            log.info("purged {} published outbox rows older than 30 days", deleted);
        }
    }
}
