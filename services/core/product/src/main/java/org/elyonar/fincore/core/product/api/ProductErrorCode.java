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
     * A rule names an account the institution has not opened, has closed, opened for another
     * purpose, or holds in another currency.
     *
     * <p>The check {@code V4__fee_account_configuration.sql} was written to make possible: a fee
     * must credit a fee income account, not any account the caller can name.
     */
    PRICING_ACCOUNT_INVALID;

    public String code() {
        return name();
    }
}
