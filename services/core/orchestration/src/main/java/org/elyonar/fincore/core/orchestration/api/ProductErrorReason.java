package org.elyonar.fincore.core.orchestration.api;

/**
 * Why a rule set was refused — the {@code reason} that accompanies {@link ProductErrorCode}'s
 * one-code-many-causes entries.
 *
 * <p>An enum rather than the string literals these were (hard rule 10: error codes belong in a
 * constant or an enum). Two things follow from that, and the second is the reason it matters
 * here. A caller branching on {@code UNKNOWN_KYC_TIER} is branching on a name that now has one
 * spelling in one place. And {@code ErrorCodeCatalogTest} can see them: it reconciles {@code
 * api.md} against the constants that exist, in both directions, so a documented reason nothing
 * raises and a raised reason nothing documents each fail the build. As literals they were
 * invisible to it — thirteen of them documented in {@code api.md} and reported as phantoms,
 * because no constant answered to the name.
 *
 * <p>Distinct from {@link ProductDecision.Refusal}, which answers "may this operation proceed"
 * on the money path. These answer "is this rule set well-formed" while it is being authored,
 * before any money is in question.
 */
public enum ProductErrorReason {

    // --- the shape of a rule ------------------------------------------------------------------

    /** An operation this schema does not price. */
    UNKNOWN_OPERATION,

    /** A fee basis outside the supported set. */
    UNKNOWN_FEE_BASIS,

    /** A KYC tier the platform does not define. */
    UNKNOWN_KYC_TIER,

    /** A channel outside the closed set (ADR 0012 — the channel is permission-gated, not free text). */
    UNKNOWN_CHANNEL,

    /** A limit type this schema does not hold. */
    UNKNOWN_LIMIT_TYPE,

    // --- the values in a rule -----------------------------------------------------------------

    /** A minimum above its maximum: a bound that admits nothing. */
    BOUNDS_INVERTED,

    /** An amount that is not an integer count of minor units (hard rule 1). */
    AMOUNT_MALFORMED,

    /** Not an uppercase ISO 4217 code. */
    CURRENCY_INVALID,

    /** A rate outside the permitted range. */
    RATE_OUT_OF_RANGE,

    /** Absent, or otherwise unusable as the moment the version takes effect. */
    EFFECTIVE_FROM_INVALID,

    // --- the accounts a rule names ------------------------------------------------------------

    /** The ledger does not know this account. */
    ACCOUNT_NOT_FOUND,

    /**
     * The account exists and is the wrong one — wrong type, wrong status, or a currency other than
     * the rule's. One reason for three, because the remedy is identical: name a different account.
     */
    ACCOUNT_WRONG_TYPE
}
