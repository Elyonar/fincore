package org.elyonar.fincore.core.customer.api;

/**
 * Customer's published error catalog.
 *
 * <p>Declared in this module rather than in one platform-wide enum. Customer may not import from
 * Orchestration or Product — modules integrate through published interfaces, and the boundary is
 * enforced by per-module database roles (ADR 0006). A shared enum would be a compile-time
 * dependency between modules that are meant to have none, so "one place" means one place per
 * module. {@code CustomerBeans} is declared here for the same reason.
 *
 * <p>Every constant appears in {@code services/core/docs/api.md}, enforced by
 * {@code ErrorCodeCatalogTest}. See {@code docs/conventions/error-contract.md}.
 */
public enum CustomerErrorCode {

    /** Unknown customer, or another tenant's. Deliberately indistinguishable. */
    CUSTOMER_NOT_FOUND,

    /** The tenant already numbered a customer with this external reference. */
    EXTERNAL_REF_TAKEN,

    /** That ledger account is already live-linked to a customer. */
    ACCOUNT_ALREADY_HELD,

    /** A tier change carried no reason. Tier movements are audited, so the reason is required. */
    REASON_REQUIRED,

    /** The customer already holds that tier. */
    TIER_UNCHANGED,

    /** A consent record omitted its category, channel or answer — consent is never a single flag. */
    CONSENT_INCOMPLETE;

    public String code() {
        return name();
    }
}
