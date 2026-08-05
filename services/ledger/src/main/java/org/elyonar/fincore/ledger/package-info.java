/**
 * Ledger Service — the single source of monetary truth.
 *
 * <h2>How this service is packaged</h2>
 *
 * Packages are <strong>vertical slices named after the domain</strong>, not horizontal layers.
 * There is no {@code controller}, {@code service}, {@code repository} triad, because that
 * arrangement scatters one capability across three packages and groups together classes that
 * share only a technical role. Everything about holds lives in {@code hold}; everything about
 * posting lives in {@code posting}. A reviewer asking "how does capture work?" opens one
 * directory.
 *
 * <p>The slices mirror the vocabulary of the agreed design, so a package name is always
 * traceable to a section of {@code docs/}:
 *
 * <ul>
 *   <li>{@code account}   — accounts and their balances; opening, closing, the balance row that
 *       every posting locks
 *   <li>{@code posting}   — transactions, entries, and the posting algorithm itself: the
 *       two-tier lock protocol, idempotency arbitration, reversal and compensation
 *   <li>{@code hold}      — placement, release, expiry sweep, and atomic capture
 *   <li>{@code period}    — accounting periods and their close
 *   <li>{@code outbox}    — the transactional outbox and its relay contract
 *   <li>{@code invariant} — the six invariants, daily anchors, and the exposure report
 *   <li>{@code tenant}    — tenant configuration and the {@code SET LOCAL} request context
 *   <li>{@code currency}  — currency reference data and minor-unit exponents
 *   <li>{@code shared}    — money and identifier types, error catalog; depended on by slices,
 *       depending on none of them
 * </ul>
 *
 * <h2>Rules that hold across every slice</h2>
 *
 * <ul>
 *   <li>Money is integer minor units. No {@code float}, {@code double}, or decimal type ever
 *       touches a money value — enforced by ArchUnit, not by review.
 *   <li>No synchronous outbound call and no event consumer exists anywhere in this service.
 *       Its behaviour is determined solely by the API calls it receives.
 *   <li>Correctness that can live in the schema does live in the schema. Application code is
 *       the second line of defence, never the only one.
 * </ul>
 *
 * @see <a href="../../../../../../docs/design.md">docs/design.md</a> — the agreed design (v1.0)
 */
package org.elyonar.fincore.ledger;
