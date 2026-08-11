package org.elyonar.fincore.core.orchestration.internal.outbox;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.elyonar.fincore.events.DomainEvent;
import org.elyonar.fincore.events.EventPublisher;
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

    /**
     * The outbox tables this relay serves — one per emitting module. A module absent from this list
     * is a module whose events never leave the building, so the list lives here, greppable, rather
     * than discovered per table.
     *
     * <p>Lending's was the second (ADR 0013) and went with the module. A name left here for a table
     * that no longer exists is not harmless: the relay reads every entry on every tick, so one
     * missing table fails the whole sweep and no module's events leave at all.
     */
    private static final List<String> OUTBOXES = List.of("orchestration.outbox_events");

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
        int published = 0;
        for (String outbox : OUTBOXES) {
            published += publishBatch(outbox, batchSize);
        }
        return published;
    }

    private int publishBatch(String outbox, int batchSize) {
        List<DomainEvent> pending =
                jdbc.query(
                        """
                        SELECT id, tenant_id, event_type, aggregate_id, created_at, epoch,
                               payload::text AS payload
                          FROM %s
                         WHERE published_at IS NULL
                         ORDER BY id
                           FOR UPDATE SKIP LOCKED
                         LIMIT ?
                        """.formatted(outbox),
                        // Every envelope field comes from this row (ADR 0008), and the envelope
                        // itself is rendered by libs/events — one renderer for every publisher,
                        // because two services each assembling "the same" JSON is how this
                        // platform ended up with two envelopes and no consumer to notice.
                        // occurredAt is created_at: when the state change committed, never when a
                        // relay got round to it.
                        (rs, row) ->
                                new DomainEvent(
                                        rs.getLong("id"),
                                        rs.getString("event_type"),
                                        rs.getString("aggregate_id"),
                                        rs.getString("tenant_id"),
                                        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
                                        rs.getLong("epoch"),
                                        rs.getString("payload")),
                        batchSize);

        // Only what the broker acknowledged is marked published: an unacknowledged event stays
        // pending and is retried, and one failure never strands the rest of the batch behind it.
        List<Long> acknowledged = publisher.publish(pending);
        for (Long id : acknowledged) {
            jdbc.update("UPDATE " + outbox + " SET published_at = now() WHERE id = ?", id);
        }
        return acknowledged.size();
    }

    /**
     * How old the oldest unpublished event is.
     *
     * <p>The signal a monitor alerts on. A relay that has stopped is otherwise invisible until
     * someone notices a consumer has gone quiet, which is typically at month-end reconciliation.
     */
    public Optional<Long> oldestPendingAgeSeconds() {
        Long oldest = null;
        for (String outbox : OUTBOXES) {
            Long age =
                    jdbc.queryForObject(
                            "SELECT FLOOR(EXTRACT(EPOCH FROM (now() - MIN(created_at))))::bigint FROM "
                                    + outbox + " WHERE published_at IS NULL",
                            Long.class);
            if (age != null && (oldest == null || age > oldest)) {
                oldest = age;
            }
        }
        return Optional.ofNullable(oldest);
    }

    public void warnIfStale(long thresholdSeconds) {
        oldestPendingAgeSeconds()
                .filter(age -> age > thresholdSeconds)
                .ifPresent(age -> log.error("core outbox relay is stale: oldest unpublished event is {}s old", age));
    }
}
