package org.elyonar.fincore.events;

import java.time.Instant;
import java.util.Objects;

/**
 * One event claimed from a service's outbox, carrying the platform envelope of ADR 0008.
 *
 * <p><strong>The envelope is rendered here, once, for every publisher.</strong> It used to be each
 * service's job, and the result was exactly what ADR 0008 was written to prevent: the ledger
 * emitted a flat body with the epoch named {@code ledgerEpoch} and no event id on the wire at all,
 * while Core emitted a nested envelope with the epoch named {@code epoch}; neither carried
 * {@code occurredAt}. Two envelopes, and the ledger's deduplication key — the thing that makes
 * at-least-once survivable — never reached a consumer. Nothing had ever consumed an event, so
 * nothing caught it.
 *
 * <p>A shared instruction to build the same JSON is a convention. A shared renderer is a
 * guarantee, and the difference only shows up once there are consumers to break.
 *
 * @param id the publishing service's outbox row id. <strong>The deduplication key</strong> —
 *     unique and monotonic per publisher and stable across redeliveries, which is what makes
 *     at-least-once survivable for a consumer.
 * @param eventType dotted domain name, e.g. {@code posting.completed}. Also the topic suffix and
 *     the routing key.
 * @param aggregateId the entity this concerns, and the partition key. Keying on it is what makes
 *     per-aggregate ordering a real guarantee rather than a hope — and it implies nothing global.
 * @param tenantId whose event this is. Always present, so no consumer writes its own
 *     tenancy-extraction bug.
 * @param occurredAt when the state change committed, never when it was relayed. A consumer that
 *     only cares about the present — a notifier, say — needs this to reject stale events after a
 *     replay, and it cannot derive it from anything else on the message.
 * @param epoch the publisher's restore generation. A consumer discards events from an epoch newer
 *     than the one it has been told to trust; without it a post-restore replay is
 *     indistinguishable from ordinary redelivery.
 * @param payload thin, and already serialized by the owning service. This library does not know
 *     what is in it and deliberately cannot: a payload is a domain's published contract.
 */
public record DomainEvent(
        long id,
        String eventType,
        String aggregateId,
        String tenantId,
        Instant occurredAt,
        long epoch,
        String payload) {

    /**
     * Rejects a partial envelope at construction.
     *
     * <p>A publisher that forgets a field should fail on its first relay pass, loudly, rather than
     * emit {@code "tenantId":"null"} to a consumer that will faithfully act on it.
     */
    public DomainEvent {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(payload, "payload");
    }

    /**
     * The ADR 0008 envelope, as the JSON that goes on the wire.
     *
     * <p>Field order matches the ADR's table so the document and the bytes read the same way.
     * {@code payload} is embedded as JSON rather than as a quoted string — a consumer parses one
     * document, never a string it has to parse again.
     *
     * <p>Written by hand rather than by a mapper, matching the outbox writers' own reasoning: this
     * shape is a published contract, and a mapper lets it drift with whatever object was passed.
     */
    public String envelope() {
        return "{\"eventId\":"
                + id
                + ",\"eventType\":\""
                + escape(eventType)
                + "\",\"aggregateId\":\""
                + escape(aggregateId)
                + "\",\"tenantId\":\""
                + escape(tenantId)
                + "\",\"occurredAt\":\""
                + occurredAt.toString()
                + "\",\"epoch\":"
                + epoch
                + ",\"payload\":"
                + payload
                + "}";
    }

    private static String escape(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
