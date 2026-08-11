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

    /**
     * The proposed code is not a well-formed unit code.
     *
     * <p>A unit code is not free text. It is copied verbatim into the {@code units} claim that
     * identity provisioning derives from these rows, so it is read by token consumers rather than
     * by people, and it is permanent — nothing renames a unit, because a reused or rewritten code
     * makes an old till's branch ambiguous in an audit. A code carrying spaces or punctuation is
     * therefore not a cosmetic problem to tidy up later; it is a claim value that can never be
     * corrected.
     */
    UNIT_CODE_INVALID,

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
