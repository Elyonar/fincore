package org.elyonar.fincore.ledger.shared;

/**
 * Sub-classifications for codes that span several distinct causes.
 *
 * <p>{@link ErrorCode#LIMIT_EXCEEDED} alone tells a client that something was out of bounds, but
 * not whether the amount was a decimal, the entry count too high, or a required field missing —
 * three failures a user-facing message must word completely differently. Without this, the only
 * place that information lived was English prose, which no French-speaking channel can use.
 *
 * <p>A reason is part of the contract: renaming one is an API change, exactly like renaming a code.
 */
public final class ErrorReason {

    private ErrorReason() {}

    // LIMIT_EXCEEDED
    public static final String FIELD_REQUIRED = "FIELD_REQUIRED";
    public static final String AMOUNT_NOT_INTEGER = "AMOUNT_NOT_INTEGER";
    public static final String AMOUNT_NOT_PARSEABLE = "AMOUNT_NOT_PARSEABLE";
    public static final String AMOUNT_ABOVE_CAP = "AMOUNT_ABOVE_CAP";
    public static final String AMOUNT_NOT_POSITIVE = "AMOUNT_NOT_POSITIVE";
    public static final String ENTRY_COUNT_EXCEEDED = "ENTRY_COUNT_EXCEEDED";
    public static final String IDEMPOTENCY_KEY_TOO_LONG = "IDEMPOTENCY_KEY_TOO_LONG";
    public static final String HOLD_EXPIRY_REQUIRED = "HOLD_EXPIRY_REQUIRED";

    // ACCOUNT_NOT_FOUND — deliberately indistinguishable to callers at the HTTP layer, but the
    // reason is carried so an operator reading a log knows which lookup failed.
    public static final String UNKNOWN_ACCOUNT = "UNKNOWN_ACCOUNT";
    public static final String UNKNOWN_TENANT = "UNKNOWN_TENANT";
    public static final String UNKNOWN_TRANSACTION = "UNKNOWN_TRANSACTION";
    public static final String UNKNOWN_HOLD = "UNKNOWN_HOLD";

    // VALUE_DATE_INVALID
    public static final String VALUE_DATE_MALFORMED = "VALUE_DATE_MALFORMED";
    public static final String VALUE_DATE_IN_FUTURE = "VALUE_DATE_IN_FUTURE";
    public static final String BACKDATE_WINDOW_EXCEEDED = "BACKDATE_WINDOW_EXCEEDED";
    public static final String BACKDATE_REASON_REQUIRED = "BACKDATE_REASON_REQUIRED";
    public static final String PERIOD_CLOSED = "PERIOD_CLOSED";
    public static final String PERIOD_ALREADY_CLOSED = "PERIOD_ALREADY_CLOSED";
    public static final String STATEMENT_PERIOD_INVALID = "STATEMENT_PERIOD_INVALID";
    public static final String CURSOR_MALFORMED = "CURSOR_MALFORMED";

    // CURRENCY_UNKNOWN — the code this institution configured is not in the ledger's registry.
    public static final String UNKNOWN_CURRENCY = "UNKNOWN_CURRENCY";

    // UNBALANCED
    public static final String TOO_FEW_ENTRIES = "TOO_FEW_ENTRIES";
    public static final String CURRENCY_NOT_BALANCED = "CURRENCY_NOT_BALANCED";
    public static final String ENTRIES_REQUIRED = "ENTRIES_REQUIRED";

    // CLOSE_BLOCKED
    public static final String ACCOUNT_ALREADY_CLOSED = "ACCOUNT_ALREADY_CLOSED";
    public static final String BALANCE_NOT_ZERO = "BALANCE_NOT_ZERO";
    public static final String ACTIVE_HOLDS_PRESENT = "ACTIVE_HOLDS_PRESENT";

    // SWEEP_INVALID
    public static final String SWEEP_NOT_SINGLE_CLOSED_ACCOUNT = "SWEEP_NOT_SINGLE_CLOSED_ACCOUNT";
    public static final String SWEEP_DOES_NOT_ZERO = "SWEEP_DOES_NOT_ZERO";
    public static final String SWEEP_COUNTERPARTY_NOT_SUSPENSE = "SWEEP_COUNTERPARTY_NOT_SUSPENSE";

    // HOLD_NOT_ACTIVE
    public static final String HOLD_ALREADY_RESOLVED = "HOLD_ALREADY_RESOLVED";
    public static final String HOLD_NOT_ON_TOUCHED_ACCOUNT = "HOLD_NOT_ON_TOUCHED_ACCOUNT";
}
