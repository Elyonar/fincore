package org.elyonar.fincore.core.orchestration.internal.outbox;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.elyonar.fincore.core.orchestration.api.CoreProperties;

/**
 * Moves committed events out of the outbox and onto the backbone.
 *
 * <p><strong>The poll is deliberately not a watermark.</strong> It selects unpublished rows rather
 * than "id greater than the last one seen", and the difference is not stylistic: sequence values are
 * assigned at <em>insert</em>, not at commit, so a slow transaction can commit id 100 after id 105
 * was already relayed. A watermark skips 100 forever — silently, with no error anywhere. The ledger
 * states this as binding in its architecture, and it is just as binding here.
 *
 * <p>Delivery is at-least-once and consumers deduplicate on {@code (publisher, eventId)}. Marking
 * published happens in the same transaction that observed the broker's acknowledgement, so a crash
 * between the two redelivers rather than loses.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final EventPublisher publisher;

    public OutboxRelay(@Qualifier(CoreProperties.Beans.RELAY_JDBC) JdbcTemplate jdbc, EventPublisher publisher) {
        this.jdbc = jdbc;
        this.publisher = publisher;
    }

    /**
     * Publishes one batch and marks it.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} lets several instances relay concurrently without
     * queueing behind each other or double-publishing a row.
     *
     * @return how many were published
     */
    @Transactional(transactionManager = CoreProperties.Beans.RELAY_TX)
    public int publishBatch(int batchSize) {
        List<PendingEvent> pending =
                jdbc.query(
                        """
                        SELECT id, tenant_id, event_type, aggregate_id, epoch, payload::text AS payload
                          FROM orchestration.outbox_events
                         WHERE published_at IS NULL
                         ORDER BY id
                           FOR UPDATE SKIP LOCKED
                         LIMIT ?
                        """,
                        (rs, row) ->
                                new PendingEvent(
                                        rs.getLong("id"),
                                        rs.getObject("tenant_id", java.util.UUID.class),
                                        rs.getString("event_type"),
                                        rs.getString("aggregate_id"),
                                        rs.getLong("epoch"),
                                        rs.getString("payload")),
                        batchSize);

        for (PendingEvent event : pending) {
            publisher.publish(event);
            jdbc.update(
                    "UPDATE orchestration.outbox_events SET published_at = now() WHERE id = ?",
                    event.id());
        }
        return pending.size();
    }

    /**
     * How old the oldest unpublished event is.
     *
     * <p>The signal a monitor alerts on. A relay that has stopped is otherwise invisible until
     * someone notices a consumer has gone quiet, which is typically at month-end reconciliation.
     */
    public Optional<Long> oldestPendingAgeSeconds() {
        Long age =
                jdbc.queryForObject(
                        "SELECT FLOOR(EXTRACT(EPOCH FROM (now() - MIN(created_at))))::bigint"
                                + " FROM orchestration.outbox_events WHERE published_at IS NULL",
                        Long.class);
        return Optional.ofNullable(age);
    }

    void warnIfStale(long thresholdSeconds) {
        oldestPendingAgeSeconds()
                .filter(age -> age > thresholdSeconds)
                .ifPresent(age -> log.error("core outbox relay is stale: oldest unpublished event is {}s old", age));
    }
}
