/**
 * Notification Service — the platform's first event consumer.
 *
 * <h2>How this service is packaged</h2>
 *
 * One public class at the root and everything else under {@code internal}, which is the boundary
 * this service is built around: nothing outside it may reach in, and there is currently no
 * {@code api} package because no other service calls this one. Notification is reached by events
 * and by an operator over HTTP, never by another deployable.
 *
 * <p>Within {@code internal}, packages are <strong>vertical slices named after the stage of the
 * pipeline</strong> rather than horizontal layers. A reviewer asking "why was this message not
 * sent?" opens one directory:
 *
 * <ul>
 *   <li>{@code intake}   — the envelope, deduplication, epoch fencing, the staleness guard, and
 *       the decision to owe a message or record a suppression. The Kafka listener is a thin
 *       adapter over it, so the whole contract is testable without a broker
 *   <li>{@code policy}   — what a tenant configured: channel order per category, quiet hours, and
 *       the rule that a transactional alert is never silenced
 *   <li>{@code template} — the published versions and the rendering of them, which is total or
 *       does not run
 *   <li>{@code channel}  — the registry, the {@code MessageSender} seam, its adapters, and the
 *       segment arithmetic that makes a Yoruba template cost what it actually costs
 *   <li>{@code contact}  — the two questions asked of Core on every send, and the deliberate
 *       refusal to cache either answer
 *   <li>{@code send}     — the queue: claim by lease, attempt, record, retry or exhaust
 *   <li>{@code api}      — the operator surface. Templates, policy, deliveries and suppressions
 * </ul>
 *
 * <h2>Rules that hold across every slice</h2>
 *
 * <ul>
 *   <li><strong>Every consumed event reaches exactly one terminal answer</strong> — a message, or a
 *       suppression carrying a reason code. There is no third outcome and no silent drop, because
 *       "why did my customer not get this?" must be a query rather than an inference from logs.
 *   <li>No money moves here, no event is published, and no gateway credential is held. The POM is
 *       where a reviewer checks the last of those: no provider SDK appears in it.
 *   <li>A channel is data, not an enum. Nothing in the intake, deduplication, policy engine or
 *       queue is channel-aware — they read a descriptor.
 *   <li>An unknown send outcome is retried, never treated as failure. A duplicate debit alert is an
 *       annoyance; a missing one is a fraud control that never fired.
 * </ul>
 *
 * @see <a href="../../../../../../docs/design.md">docs/design.md</a> — the agreed design
 */
package org.elyonar.fincore.notification;
