/**
 * Orchestration — the conductor, and the only caller of the Ledger's write API.
 *
 * <h2>How this module is packaged</h2>
 *
 * {@code api} holds the commands and results a channel names; {@code internal} holds the saga
 * engine, the ledger client, the outbox and the approval machinery. Nothing depends on this module,
 * and it is the only one permitted to declare the ledger client — enforced by the POM graph, by
 * ArchUnit, and by a database role granted on this schema alone (AGENTS.md hard rule 3, ADR 0006).
 *
 * <h2>Rules that hold here</h2>
 *
 * <ul>
 *   <li><strong>Three outcomes, never two:</strong> {@code SUCCESS}, {@code DEFINITE_FAILURE},
 *       {@code UNKNOWN}. Compensation is legal only from a definite failure. Collapsing unknown
 *       into failure double-credits when the original committed; collapsing it into success loses
 *       money. This is the decision the module exists to get right.
 *   <li><strong>Idempotency keys are a pure function of {@code (saga_id, step)}</strong> — never
 *       random, never time-derived. The Ledger's contract binds its caller to retry the *same* key
 *       on an unknown outcome, and a fresh key per attempt would make that unsatisfiable precisely
 *       in the case it exists to prevent.
 *   <li><strong>The saga row commits before the first outbound call.</strong> A crash in the window
 *       between calling and recording that we called is otherwise an orphan posting nobody knows
 *       about.
 *   <li><strong>Limits are reservations, not checks.</strong> Two concurrent transfers that each
 *       observe the limit unbreached would both proceed; the reservation is taken in the same local
 *       transaction that creates the saga.
 *   <li>Work is claimed from the database with leases, never from an in-JVM queue: several
 *       instances run behind a load balancer, and an in-memory queue loses work on restart and
 *       duplicates it across replicas.
 * </ul>
 */
package org.elyonar.fincore.core.orchestration;
