/**
 * The event backbone: one publisher abstraction, one envelope, three adapters.
 *
 * <p><strong>What lives here.</strong> The {@code EventPublisher} seam, the Kafka, RabbitMQ and
 * logging implementations of it, and {@link org.elyonar.fincore.events.DomainEvent} — which is both
 * the batch element a relay hands over and the renderer of ADR 0008's envelope.
 *
 * <p><strong>What deliberately does not.</strong> Any outbox. A relay reads its own service's
 * schema, under its own role, with its own tenancy rules; a library that owned the table would have
 * to know every service's schema and would become the place every schema change lands.
 *
 * <p><strong>The envelope is rendered here and nowhere else</strong>, and that is the whole reason
 * this package exists in its current shape. It used to be each service's job, and the two
 * publishers disagreed: the ledger emitted a flat body with the epoch named {@code ledgerEpoch} and
 * no event id on the wire at all, while Core emitted a nested envelope with the epoch named
 * {@code epoch}, and neither carried {@code occurredAt}. Nothing consumed events, so nothing
 * failed. A shared instruction to build the same JSON is a convention; a shared renderer is a
 * guarantee, and the difference only shows up when there are consumers to break.
 *
 * <p><strong>The batch shape is load-bearing.</strong> Publishing returns the ids the broker
 * acknowledged rather than succeeding or throwing as a whole: a batch whose third send fails must
 * still mark the first two published, or one unlucky event stalls everything behind it forever.
 *
 * <p><strong>The broker is a runtime choice</strong> (ADR 0005). {@code fincore.events.broker}
 * selects the adapter, no broker type reaches any domain, and the logging adapter delivers nothing
 * and says so loudly at startup — because a component that silently does its job badly is worse
 * than one that fails.
 */
package org.elyonar.fincore.events;
