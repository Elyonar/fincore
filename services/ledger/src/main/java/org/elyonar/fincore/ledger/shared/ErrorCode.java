package org.elyonar.fincore.ledger.shared;

/**
 * The ledger's error catalog, exactly as published in {@code docs/api.md}.
 *
 * <p>These are part of the contract, not implementation detail. Orchestration branches on them —
 * most importantly on whether a code is terminal for an idempotency key — so a code's meaning may
 * not drift without a design amendment.
 */
public enum ErrorCode {

    /** Per-currency sum of debits does not equal credits, or fewer than two entries. */
    UNBALANCED,

    /** An account appears on both the debit and credit side: movement of nothing. */
    WASH_TRANSACTION,

    /** Entry count, amount, or key length exceeds its documented bound. */
    LIMIT_EXCEEDED,

    /** Unknown account — or another tenant's, which is deliberately indistinguishable. */
    ACCOUNT_NOT_FOUND,

    /** A non-reversal, non-sweep posting touches a closed account. */
    ACCOUNT_CLOSED,

    /** Entry or hold currency differs from the account's. */
    CURRENCY_MISMATCH,

    /** A guarded account would fall below zero available. */
    INSUFFICIENT_FUNDS,

    /**
     * Same idempotency key, different payload fingerprint. A caller bug rather than a race, and
     * terminal for that key: retrying cannot make two different payloads the same request.
     */
    IDEMPOTENCY_KEY_REUSED,

    /** Future date, outside the backdate window, missing reason, or a closed period. */
    VALUE_DATE_INVALID,

    /**
     * Capture attempted against a hold that is RELEASED, EXPIRED or already CONSUMED. Terminal
     * for that hold: the reservation is gone and re-authorisation is the only honest recovery.
     */
    HOLD_NOT_ACTIVE,

    /** The debit against the held account exceeds the amount reserved. */
    HOLD_EXCEEDED,

    /**
     * The target is already reversed. The response carries the winning reversal's id so a saga
     * converges on it instead of retry-looping against a state that will never change.
     */
    ALREADY_REVERSED,

    /**
     * The target is itself a reversal. Reversing a reversal silently resurrects money movement
     * while every status still reads terminal; the correct correction is a fresh transaction.
     */
    REVERSAL_OF_REVERSAL,

    /**
     * The target carries compensations, so a plain full reversal would double-credit on top of a
     * partial refund already given.
     */
    HAS_COMPENSATIONS,

    /**
     * Compensating an already-reversed transaction — the same double credit as
     * {@link #HAS_COMPENSATIONS}, with the operations in the other order.
     */
    TARGET_REVERSED,

    /** Closure attempted with a nonzero balance or active holds. */
    CLOSE_BLOCKED,

    /** A sweep that does not exactly zero a closed account, or lacks a suspense counterparty. */
    SWEEP_INVALID;

    /** The stable string clients match on; never derived from the enum's position. */
    public String code() {
        return name();
    }
}
