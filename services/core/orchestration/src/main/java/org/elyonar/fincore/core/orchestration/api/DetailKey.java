package org.elyonar.fincore.core.orchestration.api;

/**
 * The keys that appear in an error's {@code details} map.
 *
 * <p>These are contract: a channel's message template interpolates them by name, so a renamed key
 * silently blanks a placeholder in somebody's rendered sentence. Naming them here rather than
 * writing the string at each throw site is what makes that rename one edit instead of a search,
 * and what lets {@code api.md} document a fixed set rather than whatever happens to be produced.
 */
public final class DetailKey {

    private DetailKey() {}

    /** The request field a validation failure is about. */
    public static final String FIELD = "field";

    /** What the caller actually supplied, verbatim. */
    public static final String SUPPLIED = "supplied";

    /** The bound that was broken. */
    public static final String LIMIT = "limit";

    /** ISO 4217 code, when a failure is currency-specific. */
    public static final String CURRENCY = "currency";

    /** The institution's own short reference for a thing it named — an internal account's code. */
    public static final String CODE = "code";

    /** The saga a caller can poll when an outcome is unknown. */
    public static final String TRANSACTION_ID = "transactionId";

    /** The winning reversal's id, so a saga converges instead of retry-looping. */
    public static final String REVERSAL_ID = "reversalId";

    /** The maximum length a derived idempotency key may reach. */
    public static final String MAX_LENGTH = "maxLength";

    /**
     * The Ledger's own error code, when Core relays a refusal it does not model.
     *
     * <p>For an operator reading a log. A channel branches on Core's code, never on this — the two
     * catalogs are separate contracts and merging them is a decision, not a default.
     */
    public static final String LEDGER_CODE = "ledgerCode";
}
