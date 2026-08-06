package org.elyonar.fincore.core.orchestration.internal.outbox;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes a domain event in the same database transaction as the state change it describes.
 *
 * <p>That is the whole point of an outbox: an event exists <em>if and only if</em> the change
 * committed. Publishing from application code after a commit leaves a window in which the change is
 * durable and the event was never sent, and no amount of retrying closes it — the process can die
 * inside the window.
 *
 * <p>No method here starts a transaction. Every one must be called from inside the transaction
 * doing the state change, which is why they are package-visible to the saga code rather than a
 * service anything can reach.
 */
@Component
public class OutboxWriter {

    private final JdbcTemplate jdbc;

    public OutboxWriter(@Qualifier("orchestrationJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Appends an event to the outbox.
     *
     * @param aggregateId the entity this is about. Also the partition key, which is what makes
     *     per-aggregate ordering a real guarantee rather than a hope (ADR 0008).
     * @param payload thin: identifiers plus the minimum a consumer needs to decide whether to care.
     *     Money inside it is a decimal string, matching every other JSON this platform emits.
     */
    public void append(UUID tenantId, String eventType, UUID aggregateId, String payload) {
        jdbc.update(
                """
                INSERT INTO orchestration.outbox_events (tenant_id, event_type, aggregate_id, payload)
                VALUES (?, ?, ?, ?::jsonb)
                """,
                tenantId,
                eventType,
                aggregateId.toString(),
                payload);
    }
}
