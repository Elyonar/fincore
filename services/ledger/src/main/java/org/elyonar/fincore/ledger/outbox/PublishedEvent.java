package org.elyonar.fincore.ledger.outbox;

/** One event claimed from the outbox, ready to hand to a broker. */
public record PublishedEvent(long id, String eventType, String aggregateId, String payload) {}
