/**
 * Customer — who the tenant's customers are.
 *
 * <h2>How this module is packaged</h2>
 *
 * Two packages, and the split is the module boundary itself (ADR 0006):
 *
 * <ul>
 *   <li>{@code api} — the published surface. What Orchestration is allowed to know, and nothing
 *       else. Deliberately narrow
 *   <li>{@code internal} — everything else: the records, the administrative HTTP surface, the
 *       schema. No other module may reference it, enforced by {@code ModuleBoundaryTest} and by a
 *       database role granted only on this schema
 * </ul>
 *
 * <p>The narrowness of {@code api} is load-bearing rather than tidy. It is what keeps the money
 * path free of a dependency on PII, and what lets this module become its own deployable by turning
 * two methods into a client — which ADR 0006 names as the first extraction likely to be needed,
 * because this is the only module holding personal data.
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
