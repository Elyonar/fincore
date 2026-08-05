package org.elyonar.fincore.ledger.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Exposes the outbox's health as metrics, not just log lines.
 *
 * <p>{@code architecture.md} requires an alert when the oldest unpublished event is more than sixty
 * seconds old, because a dead relay is otherwise completely silent — postings keep succeeding,
 * nothing errors, and the first sign of trouble is a reconciliation failure at month end. That
 * requirement was implemented as {@code log.error}, which alerts whoever happens to be grepping.
 *
 * <p>Two gauges, scraped from {@code /actuator/prometheus}:
 *
 * <ul>
 *   <li>{@code ledger.outbox.pending} — how many events are waiting
 *   <li>{@code ledger.outbox.oldest_pending_seconds} — the number the alert is actually written
 *       against
 * </ul>
 *
 * <p>Both are read straight from the table rather than counted in memory, so a relay that has
 * stopped running still reports honestly. An in-memory counter maintained by the relay would go
 * quiet exactly when the relay did.
 */
@Component
public class OutboxMetrics {

    private final JdbcTemplate jdbc;

    public OutboxMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;

        registry.gauge(
                "ledger.outbox.pending",
                this,
                self -> self.scalar("SELECT count(*) FROM outbox_events WHERE published_at IS NULL"));

        registry.gauge(
                "ledger.outbox.oldest_pending_seconds",
                this,
                self ->
                        self.scalar(
                                "SELECT COALESCE(FLOOR(EXTRACT(EPOCH FROM (now() - MIN(created_at))))::bigint, 0)"
                                        + " FROM outbox_events WHERE published_at IS NULL"));
    }

    /**
     * Reads one number, in relay scope so RLS does not hide the queue.
     *
     * <p>Returns {@code long}, not {@code double}, even though Micrometer's gauge takes a
     * {@code ToDoubleFunction}: the no-floating-point rule is enforced across the whole service
     * rather than only on money, and an absolute rule needs no judgement at review time. The
     * widening happens at the lambda boundary above, so no method here declares a floating-point
     * return.
     *
     * <p>Never throws: a broken gauge must read zero rather than take the scrape endpoint down with
     * it, since that endpoint is also how liveness is observed.
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    long scalar(String sql) {
        try {
            jdbc.queryForObject("SELECT set_config('app.relay', 'on', true)", String.class);
            Long value = jdbc.queryForObject(sql, Long.class);
            return value == null ? 0L : value;
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
