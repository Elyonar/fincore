package org.elyonar.fincore.core.orchestration.internal.outbox;

import java.util.UUID;

/**
 * One event, as the relay reads it, carrying the platform envelope (ADR 0008).
 *
 * @param id the outbox row id. <strong>The deduplication key</strong> — unique and monotonic per
 *     publisher, and stable across redeliveries, which is what makes at-least-once survivable.
 * @param aggregateId the entity this concerns, and the partition key
 * @param epoch the publisher's restore generation. A consumer discards events from an epoch newer
 *     than the one it was told to trust, so a restored publisher cannot assert states the consumer
 *     has already been told to distrust.
 * @param payload thin, and never database-shaped: a payload is a published contract, a table is an
 *     implementation detail
 */
public record PendingEvent(
        long id, UUID tenantId, String eventType, String aggregateId, long epoch, String payload) {}
