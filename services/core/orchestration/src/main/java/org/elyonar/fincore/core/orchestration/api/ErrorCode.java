package org.elyonar.fincore.core.orchestration.api;

/**
 * Core's published error catalog.
 *
 * <p>An enum rather than string literals because these values are thrown in service logic,
 * branched on by callers, listed in {@code docs/api.md} and asserted in tests. Held as strings they
 * were four copies that had to change together, and one of them — the HTTP layer — was not a code
 * at all: it forwarded {@code IllegalArgumentException.getMessage()}, so a caller branching on
 * {@code code} was branching on an English sentence.
 *
 * <p>Every constant here appears in {@code services/core/docs/api.md}, enforced by
 * {@code ErrorCodeCatalogTest}. See {@code docs/conventions/error-contract.md}.
 */
public enum ErrorCode {

    // ---- eligibility ----------------------------------------------------------------
    /** Unknown customer, or another tenant's. Deliberately indistinguishable. */
    CUSTOMER_NOT_FOUND,
    /** The customer is dormant or closed. */
    CUSTOMER_NOT_ACTIVE,
    /** The account is not linked to this customer. */
    ACCOUNT_NOT_LINKED,

    // ---- product --------------------------------------------------------------------
    /** No product, or no published version in effect. */
    PRODUCT_NOT_FOUND,
    /** The product forbids this operation for this tier or channel. */
    OPERATION_NOT_PERMITTED,
    /** A per-transaction or daily limit would be breached. */
    LIMIT_EXCEEDED,

    // ---- the command itself ---------------------------------------------------------
    /** Zero, negative, above the platform cap, or not an integer count of minor units. */
    AMOUNT_INVALID,
    /** Source and destination are the same account. */
    WASH_TRANSACTION,
    /** Entry currency does not match the account's. */
    CURRENCY_MISMATCH,
    /** A required field is absent or malformed. */
    COMMAND_INVALID,

    // ---- cash -----------------------------------------------------------------------
    /** The teller's till is not open. */
    TILL_NOT_OPEN,
    /** The fee would consume more than the deposit. */
    FEE_EXCEEDS_DEPOSIT,

    // ---- relayed from the Ledger ----------------------------------------------------
    /** The account would go available &lt; 0. */
    INSUFFICIENT_FUNDS,
    /** Same key, different payload fingerprint. A caller bug. */
    IDEMPOTENCY_KEY_REUSED,

    // ---- reversal -------------------------------------------------------------------
    /** Unknown saga, or another tenant's. */
    TRANSACTION_NOT_FOUND,
    /** Target is not COMPLETED, or is itself a reversal. */
    NOT_REVERSIBLE,
    /** Reversal without a valid maker-checker approval reference. */
    APPROVAL_REQUIRED,
    /** A reversal exists; the response carries its id. */
    ALREADY_REVERSED,
    /**
     * The approval does not authorise this reversal — wrong target, wrong amount, unapproved, or
     * already spent. Which of the four stays in the log: naming it would tell a prober what a
     * valid approval must look like.
     */
    APPROVAL_INVALID,

    // ---- the outcome protocol -------------------------------------------------------
    /**
     * The Ledger could not be reached at all — a refused connection, never an ambiguous one. The
     * one network failure that is definite, because nothing was ever sent.
     */
    LEDGER_UNREACHABLE,
    /**
     * The outcome is not known. Returned as 503 so the caller retries the same key, never as a
     * success-shaped 202.
     */
    OUTCOME_UNKNOWN,

    /**
     * The Ledger refused for a reason Core does not model.
     *
     * <p>Deliberately explicit rather than passing the Ledger's own string through as if it were a
     * Core code. A caller reading Core's catalog would find no such entry and no way to translate
     * it, and the two catalogs would silently become one — so a new Ledger code would appear in
     * Core's API without anyone deciding it should. The Ledger's code travels in
     * {@code details.ledgerCode} for an operator; a channel branches on this.
     */
    LEDGER_REFUSED;

    public String code() {
        return name();
    }

    /**
     * Maps a code relayed from the Ledger onto Core's own catalog.
     *
     * <p>Only the codes Core documents as relayed are mapped. Anything else becomes
     * {@link #LEDGER_REFUSED} rather than being forwarded verbatim: a code a caller cannot find in
     * Core's catalog is a code it cannot handle or translate, and quietly widening Core's contract
     * every time the Ledger gains an error is how two catalogs merge without a decision.
     */
    public static ErrorCode fromLedger(String ledgerCode) {
        if (ledgerCode == null) {
            return LEDGER_REFUSED;
        }
        return switch (ledgerCode) {
            case "INSUFFICIENT_FUNDS" -> INSUFFICIENT_FUNDS;
            case "CURRENCY_MISMATCH" -> CURRENCY_MISMATCH;
            case "IDEMPOTENCY_KEY_REUSED" -> IDEMPOTENCY_KEY_REUSED;
            case "WASH_TRANSACTION" -> WASH_TRANSACTION;
            case "ALREADY_REVERSED" -> ALREADY_REVERSED;
            case "LIMIT_EXCEEDED" -> LIMIT_EXCEEDED;
            default -> LEDGER_REFUSED;
        };
    }
}
