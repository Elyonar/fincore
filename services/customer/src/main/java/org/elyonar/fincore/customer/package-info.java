/**
 * Customer — who the tenant's customers are.
 *
 * <h2>How this deployable is packaged</h2>
 *
 * Two packages:
 *
 * <ul>
 *   <li>{@code api} — the shapes this service answers with. What the money path is allowed to
 *       know, and nothing else. Deliberately narrow
 *   <li>{@code internal} — everything else: the records, the administrative HTTP surface, the
 *       schema
 * </ul>
 *
 * <p>The narrowness of {@code api} is load-bearing rather than tidy: it is what keeps the money
 * path free of a dependency on PII. It is also what made the extraction cheap — ADR 0006 named
 * this the first module likely to need extracting, because it holds the only personal data on the
 * platform, and [ADR 0020] carried it out by turning two methods into a client.
 *
 * <p>The split was once enforced by {@code ModuleBoundaryTest}, when another module could have
 * imported these internals from the same JVM. This is now its own process, so that is enforced by
 * construction. What {@code BoundaryTest} enforces here instead is ADR 0020's obligation: no
 * client onto the money path, no ledger client at all, no money type, no sibling deployable on the
 * classpath. Core calls Customer; Customer does not call Core.
 *
 * <h2>Rules that hold here</h2>
 *
 * <ul>
 *   <li><strong>This module owns identity, never money.</strong> Balances, entries and transaction
 *       history live in the Ledger; profiles, tiers, mandates, contact details, consent and the
 *       customer-to-account mapping live here.
 *   <li><strong>Only explicit answers are stored.</strong> A consent record means the customer was
 *       asked and answered, and a null locale means nobody asked — what an absent answer permits is
 *       the calling service's policy. A default written here would dress an assumption up as a
 *       customer's answer.
 *   <li>Every change to a tier or a consent is attributed and kept. The current row answers what is
 *       true; only the history answers when it became true and who recorded it, which is the
 *       question that arrives with a regulator attached.
 * </ul>
 */
package org.elyonar.fincore.customer;
