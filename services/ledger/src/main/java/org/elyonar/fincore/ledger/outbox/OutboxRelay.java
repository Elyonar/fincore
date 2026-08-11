package org.elyonar.fincore.ledger.outbox;

import java.util.ArrayList;
import java.util.List;
import org.elyonar.fincore.events.DomainEvent;
import org.elyonar.fincore.events.EventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves committed events from the outbox to the bus.
 *
 * <p>The poll is <strong>never</strong> a watermark. Sequence values are assigned at insert rather
 * than at commit, so a long-running posting can commit id=100 <em>after</em> id=105 has already
 * been relayed. A relay that remembered "last id seen" would skip id=100 permanently and silently
 * — the event simply never arrives, and nothing anywhere reports an error. Selecting on
 * {@code published_at IS NULL} has no such blind spot: a row is pending until it is marked, no
 * matter when it became visible.
 *
 * <p>{@code FOR UPDATE SKIP LOCKED} lets several relay instances run without coordinating and
 * without processing the same row twice.
 *
 * <p>Ordering is per-batch only, never global. Consumers must therefore be state-based: react to an
 * event by fetching current state through the read API, never by replaying a sequence.
 */
@Component
public class OutboxRelay {

    private final JdbcTemplate jdbc;
    private final EventPublisher publisher;

    public OutboxRelay(JdbcTemplate jdbc, EventPublisher publisher) {
        this.jdbc = jdbc;
        this.publisher = publisher;
    }

    /**
     * Claims, publishes and marks up to {@code batchSize} events. Returns how many were marked.
     *
     * <p>Opts into cross-tenant visibility explicitly and for this transaction only. Without it
     * the relay is subject to tenant RLS, finds an empty queue whatever the backlog, and reports
     * success — a delivery mechanism that silently delivers nothing.
     */
    @Transactional
    public int relayBatch(int batchSize) {
        enterRelayScope();

        List<DomainEvent> claimed =
                jdbc.query(
                        """
                        SELECT id, event_type, aggregate_id, tenant_id, created_at, epoch, payload::text
                          FROM outbox_events
                         WHERE published_at IS NULL
                         ORDER BY id
                         FOR UPDATE SKIP LOCKED
                         LIMIT ?
                        """,
                        // Every envelope field comes from this row (ADR 0008). occurredAt is
                        // created_at — when the writing transaction committed — and never the
                        // moment of relay, which can be minutes later after a broker outage.
                        (rs, rowNum) ->
                                new DomainEvent(
                                        rs.getLong("id"),
                                        rs.getString("event_type"),
                                        rs.getString("aggregate_id"),
                                        rs.getString("tenant_id"),
                                        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
                                        rs.getLong("epoch"),
                                        rs.getString("payload")),
                        batchSize);

        if (claimed.isEmpty()) {
            return 0;
        }

        List<Long> acknowledged = publisher.publish(claimed);
        if (acknowledged.isEmpty()) {
            return 0;
        }

        // Marked in the same transaction that recorded the acknowledgement. An event the broker
        // did not confirm stays pending and is retried; delivery is at-least-once by design, and
        // consumers deduplicate on the outbox id.
        for (Long id : acknowledged) {
            jdbc.update("UPDATE outbox_events SET published_at = now() WHERE id = ?", id);
        }
        return acknowledged.size();
    }

    /**
     * Grants this transaction cross-tenant read of {@code outbox_events}, and nothing else.
     *
     * <p>{@code SET LOCAL}, so it dies with the transaction and cannot ride a pooled connection
     * to the next borrower. The policy in V4 permits reads under this flag but still requires a
     * matching tenant to write, so the relay can never author an event.
     */
    private void enterRelayScope() {
        jdbc.queryForObject("SELECT set_config('app.relay', 'on', true)", String.class);
    }

    /**
     * How many events are waiting to be published.
     *
     * <p>Read from the table, never counted in memory, so a relay that has stopped running still
     * reports honestly — an in-memory counter maintained by the relay would go quiet exactly when
     * the relay did.
     */
    @Transactional(readOnly = true)
    public long pendingCount() {
        enterRelayScope();
        Long pending =
                jdbc.queryForObject(
                        "SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Long.class);
        return pending == null ? 0L : pending;
    }

    /**
     * Age of the oldest unpublished event, in seconds, or empty when nothing is pending.
     *
     * <p>This is the health signal that matters: a dead relay is silent, and without it the first
     * sign of trouble is a reconciliation failure at month-end. Alert above 60 seconds.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<Long> oldestPendingAgeSeconds() {
        enterRelayScope();
        Long age =
                jdbc.queryForObject(
                        "SELECT FLOOR(EXTRACT(EPOCH FROM (now() - MIN(created_at))))::bigint"
                                + " FROM outbox_events WHERE published_at IS NULL",
                        Long.class);
        return java.util.Optional.ofNullable(age);
    }

    /**
     * Deletes published rows older than the retention window.
     *
     * <p>The outbox is a delivery queue, never a second audit archive: the entries tables are the
     * seven-year record. Letting it grow forever would create an unaudited parallel history and a
     * table nobody can index.
     */
    @Transactional
    public int purgePublishedOlderThanDays(int days) {
        enterRelayScope();
        return jdbc.update(
                "DELETE FROM outbox_events WHERE published_at IS NOT NULL"
                        + " AND published_at < now() - make_interval(days => ?)",
                days);
    }
}
