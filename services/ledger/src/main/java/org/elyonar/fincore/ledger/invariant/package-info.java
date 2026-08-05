/**
 * Verification: the ledger's argument for its own correctness.
 *
 * <p>Every check compares derived state against the entries it was derived from, so a bug in the
 * posting path surfaces as a disagreement rather than as quiet, self-consistent wrongness. Checks
 * return counterexamples rather than booleans, because "something is wrong" is not actionable at
 * three in the morning.
 */
package org.elyonar.fincore.ledger.invariant;
