package org.elyonar.fincore.core.orchestration.api;

/**
 * What a non-mutating Ledger read answered.
 *
 * <p>Three-valued for the same reason {@link LedgerOutcome} is: "the transaction is not there"
 * and "we could not ask" are different facts, and reconciliation must never record the second as
 * the first — a Core outage during the ledger's maintenance window would otherwise flag every
 * completed saga in the scan as missing money.
 */
public sealed interface LedgerRead {

    /** The transaction exists. Status is the ledger's ({@code POSTED} / {@code REVERSED}). */
    record Found(String status, long totalDebitMinor) implements LedgerRead {}

    /** Definitively absent — a 404, which the ledger also answers for another tenant's row. */
    record NotFound() implements LedgerRead {}

    /** Could not ask, or could not read the answer. Say nothing; ask again next run. */
    record Unknown(String reason) implements LedgerRead {}
}
