package org.elyonar.fincore.events;

/**
 * One event claimed from a service's outbox, ready to hand to a broker.
 *
 * @param id the publishing service's outbox row id. <strong>The deduplication key</strong> —
 *     unique and monotonic per publisher and stable across redeliveries, which is what makes
 *     at-least-once survivable for a consumer.
 * @param eventType dotted domain name, e.g. {@code posting.completed}. Also the topic suffix and
 *     the routing key.
 * @param aggregateId the entity this concerns, and the partition key. Keying on it is what makes
 *     per-aggregate ordering a real guarantee rather than a hope — and it implies nothing global.
 * @param payload thin, and already serialized by the owning service. This library does not know
 *     what is in it and deliberately cannot: a payload is a domain's published contract.
 */
public record DomainEvent(long id, String eventType, String aggregateId, String payload) {}
