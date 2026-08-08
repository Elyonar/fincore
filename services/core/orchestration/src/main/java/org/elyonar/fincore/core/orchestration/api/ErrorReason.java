package org.elyonar.fincore.core.orchestration.api;

/**
 * Sub-classifications for Core codes that span several distinct causes.
 *
 * <p>Same rule as the Ledger's: if a French translation of two failures would be different
 * sentences, they need different reasons. {@code COMMAND_INVALID} alone tells a channel that the
 * request was malformed but not whether a field was missing, an amount was negative, or a key was
 * too long — three messages a teller's screen must word completely differently.
 *
 * <p>A reason is contract. Renaming one is a MAJOR amendment, exactly like renaming a code.
 */
public final class ErrorReason {

    private ErrorReason() {}

    // COMMAND_INVALID
    public static final String FIELD_REQUIRED = "FIELD_REQUIRED";
    public static final String STEP_CONTAINS_SEPARATOR = "STEP_CONTAINS_SEPARATOR";
    public static final String DERIVED_KEY_TOO_LONG = "DERIVED_KEY_TOO_LONG";
    public static final String TOO_FEW_ENTRIES = "TOO_FEW_ENTRIES";
    public static final String IDEMPOTENCY_KEY_REQUIRED = "IDEMPOTENCY_KEY_REQUIRED";

    // AMOUNT_INVALID
    public static final String AMOUNT_NOT_POSITIVE = "AMOUNT_NOT_POSITIVE";
    public static final String CHANNEL_INVALID = "CHANNEL_INVALID";
    public static final String AMOUNT_SIGN_ON_ENTRY = "AMOUNT_SIGN_ON_ENTRY";

    // LIMIT_EXCEEDED
    public static final String PER_TRANSACTION_LIMIT = "PER_TRANSACTION_LIMIT";
    public static final String DAILY_LIMIT = "DAILY_LIMIT";

    // NOT_REVERSIBLE — one answer for several causes, deliberately. The reasons exist for the
    // operator reading a log, never to tell a caller which of the three it was: distinguishing
    // "another tenant's" from "not COMPLETED" would leak the existence of another tenant's saga.
    public static final String NOT_COMPLETED = "NOT_COMPLETED";
    public static final String IS_A_REVERSAL = "IS_A_REVERSAL";

    // OUTCOME_UNKNOWN
    public static final String READ_TIMEOUT = "READ_TIMEOUT";
    public static final String NO_TRANSACTION_ID = "NO_TRANSACTION_ID";
    public static final String UNEXPECTED_STATUS = "UNEXPECTED_STATUS";
}
