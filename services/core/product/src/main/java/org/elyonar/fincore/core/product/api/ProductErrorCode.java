package org.elyonar.fincore.core.product.api;

/**
 * Product's published error catalog.
 *
 * <p>Declared in this module for the same reason as {@code CustomerErrorCode}: Product may not
 * import from Customer or Orchestration, so a shared enum would create exactly the cross-module
 * dependency ADR 0006 forbids.
 *
 * <p>Every constant appears in {@code services/core/docs/api.md}, enforced by
 * {@code ErrorCodeCatalogTest}.
 */
public enum ProductErrorCode {

    /** No product, or no published version in effect. */
    PRODUCT_NOT_FOUND,

    /** The tenant already has a product with this code. */
    PRODUCT_CODE_TAKEN,

    /** Not one of the supported product types. */
    INVALID_PRODUCT_TYPE,

    /** No such version, or another tenant's. */
    PRODUCT_VERSION_NOT_FOUND,

    /** That version is live already; a published version is immutable. */
    VERSION_ALREADY_PUBLISHED,

    /**
     * The principal wrote this version and may not publish it.
     *
     * <p>Maker-checker: pricing that one person can both write and make live is pricing one person
     * can change unreviewed.
     */
    PUBLISHER_IS_AUTHOR,

    /**
     * A write against a version that is already live.
     *
     * <p>Distinct from {@link #VERSION_ALREADY_PUBLISHED}, which answers an attempt to publish
     * something published. This one answers an attempt to <em>edit</em> it, and the remedy differs:
     * draft the next version rather than fetch a colleague.
     */
    VERSION_NOT_DRAFT,

    /**
     * A rule set this version cannot hold.
     *
     * <p>One code spanning many causes, so it carries a {@code reason}: {@code UNKNOWN_OPERATION},
     * {@code UNKNOWN_FEE_BASIS}, {@code UNKNOWN_KYC_TIER}, {@code UNKNOWN_CHANNEL},
     * {@code UNKNOWN_LIMIT_TYPE}, {@code BOUNDS_INVERTED},
     * {@code RATE_OUT_OF_RANGE}, {@code AMOUNT_MALFORMED}, {@code CURRENCY_INVALID},
     * {@code ACCOUNT_NOT_FOUND}, {@code ACCOUNT_WRONG_TYPE}, {@code EFFECTIVE_FROM_INVALID}.
     */
    RULES_INVALID,

    /** A draft dated to become effective before it existed. */
    EFFECTIVE_FROM_IN_THE_PAST,

    /**
     * Two drafts of the same next version, concurrently; the unique index picked the winner.
     *
     * <p>409, remedy is retry. Distinct from {@link #VERSION_ALREADY_PUBLISHED}: nothing here is
     * live — the caller lost a race, not an argument.
     */
    DRAFT_CONFLICT,

    /**
     * The ledger could not be asked whether a named account is usable.
     *
     * <p>Product's own constant rather than Orchestration's: the two modules may not share an enum
     * (ADR 0006), and a duplicated name is cheaper than the dependency. A 503 — the configuration
     * may be correct and the answer merely unavailable.
     */
    LEDGER_UNREACHABLE,

    /**
     * A rule names an account the institution has not opened, or has opened for something else.
     *
     * <p>Was a string literal at the one place that returned it, which made it invisible to
     * {@code ErrorCodeCatalogTest} — {@code api.md} documented it and the test reported a phantom,
     * because no constant answered to the name. Here it is reconciled like every other code
     * (hard rule 10), and the handler returns the constant rather than a spelling of it.
     */
    PRICING_ACCOUNT_INVALID;

    public String code() {
        return name();
    }
}
