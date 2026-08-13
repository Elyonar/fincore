package org.elyonar.fincore.core.orchestration.api;

/**
 * The Customer module's answer about a customer's ability to transact.
 *
 * <p>Carries the reason rather than a bare boolean: Orchestration has to turn a refusal into a
 * specific error code, and "no" alone would force it to guess or to ask a second question.
 *
 * @param eligible whether the customer may transact at all
 * @param kycTier the tier driving limits, e.g. {@code TIER_1}. Null when not eligible.
 * @param reason why not, when not eligible. Null when eligible.
 */
public record EligibilityResult(boolean eligible, String kycTier, Reason reason) {

    /** Why a customer may not transact. Each maps to a distinct API error code. */
    public enum Reason {
        /** No such customer in this tenant. Wrong-tenant is indistinguishable, deliberately. */
        NOT_FOUND,
        /** Known, but dormant or closed. */
        NOT_ACTIVE
    }

    public static EligibilityResult eligible(String kycTier) {
        return new EligibilityResult(true, kycTier, null);
    }

    public static EligibilityResult refused(Reason reason) {
        return new EligibilityResult(false, null, reason);
    }
}
