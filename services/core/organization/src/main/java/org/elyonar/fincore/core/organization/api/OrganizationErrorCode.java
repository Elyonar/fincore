package org.elyonar.fincore.core.organization.api;

/**
 * Organization's published error catalog.
 *
 * <p>Module-local, like {@code CustomerErrorCode} and for the same reason: a shared enum would be
 * a compile-time dependency between modules that are meant to have none (ADR 0006).
 *
 * <p>Every constant appears in {@code services/core/docs/api.md}, enforced by
 * {@code ErrorCodeCatalogTest}. See {@code docs/conventions/error-contract.md}.
 */
public enum OrganizationErrorCode {

    /** Unknown unit, another tenant's, or closed. Deliberately indistinguishable. */
    UNIT_NOT_FOUND,

    /** The tenant already has a unit with this code — including a closed one; codes never recycle. */
    UNIT_CODE_TAKEN,

    /** The named parent does not exist, is another tenant's, or is closed. */
    PARENT_UNIT_NOT_FOUND,

    /** The principal already holds a live assignment to this unit. */
    ASSIGNMENT_EXISTS,

    /** No live assignment ties this principal to this unit. */
    ASSIGNMENT_NOT_FOUND;

    public String code() {
        return name();
    }
}
