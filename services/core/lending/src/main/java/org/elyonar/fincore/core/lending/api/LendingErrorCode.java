package org.elyonar.fincore.core.lending.api;

/**
 * Lending's published error catalog — module-local, catalog-tested, per
 * {@code docs/conventions/error-contract.md} and lending.md's staged catalog.
 */
public enum LendingErrorCode {

    /** Unknown loan or application, or another tenant's. Deliberately indistinguishable. */
    LOAN_NOT_FOUND,

    /** The transition the caller asked for is not legal from the current state. */
    APPLICATION_STATE_INVALID,

    /** No published LOAN version is in effect for that product code. */
    PRODUCT_NOT_LENDABLE,

    /** Outside the product version's amount bounds. */
    AMOUNT_OUT_OF_BOUNDS,

    /** Outside the product version's term bounds. */
    TERM_OUT_OF_BOUNDS,

    /** Duplicate approver, the applicant approving, or a tier already satisfied. */
    APPROVAL_SEQUENCE_INVALID,

    /** The offer's acceptance window has passed; the application is now EXPIRED. */
    OFFER_EXPIRED,

    /** The repayment exceeds the payoff. Refused at intake, never parked. */
    REPAYMENT_EXCEEDS_PAYOFF,

    /** The loan is not ACTIVE. */
    LOAN_NOT_ACTIVE;

    public String code() {
        return name();
    }
}
