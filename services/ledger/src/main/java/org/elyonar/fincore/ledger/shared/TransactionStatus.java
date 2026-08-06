package org.elyonar.fincore.ledger.shared;

/** A transaction's state, matching the {@code ledger_transactions.status} CHECK constraint. */
public enum TransactionStatus {
    POSTED,

    /** Undone by exactly one reversal. Terminal — a reversal is never itself reversed. */
    REVERSED;

    public boolean isReversed() {
        return this == REVERSED;
    }

    public static TransactionStatus of(String value) {
        return valueOf(value);
    }
}
