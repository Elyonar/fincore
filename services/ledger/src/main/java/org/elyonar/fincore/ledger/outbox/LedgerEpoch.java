package org.elyonar.fincore.ledger.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The ledger's restore generation, stamped on every published event.
 *
 * <p>The restore protocol in {@code architecture.md} rests on this. A restore from backup rewinds
 * the outbox: ids that consumers have already seen become available again, and the events written
 * under them may describe entirely different money. Without a generation marker there is no way for
 * a consumer to tell a genuine at-least-once redelivery from a post-restore replay of a different
 * history — both look like an outbox id it has seen before.
 *
 * <p>Carrying the epoch lets a consumer discard events from an epoch newer than the one it was told
 * to trust, and reconcile through the read API instead.
 *
 * <p>Read once at startup and held: the value changes only when an operator advances it during a
 * restore, at which point the service is being restarted anyway.
 */
@Component
public class LedgerEpoch {

    private final long epoch;

    public LedgerEpoch(JdbcTemplate jdbc) {
        Long current = jdbc.queryForObject("SELECT epoch FROM ledger_epoch WHERE singleton", Long.class);
        this.epoch = current == null ? 1L : current;
    }

    public long value() {
        return epoch;
    }
}
