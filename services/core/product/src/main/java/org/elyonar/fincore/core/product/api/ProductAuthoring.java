package org.elyonar.fincore.core.product.api;

import java.util.List;
import java.util.UUID;

/**
 * Writing a product's pricing (admin-surface §3).
 *
 * <p>Published rather than kept internal because the surface that drives it lives in {@code app} —
 * the one module that sees every other, and therefore the only place a fee rule's account can be
 * checked against the institution's own accounts before it is stored. Product may not reach into
 * Orchestration to ask (ADR 0006), so the composition happens above both.
 *
 * <p>Every write here targets a <em>draft</em>. A published version is immutable — the version row
 * by trigger since V2, its rules by trigger since V7 — so changing a price is creating the next
 * version, which is what {@link #draftNextVersion} exists for. That is not friction: a transaction
 * decided under version 3 has to stay explicable when version 4 exists, and it only does if 3
 * cannot be edited afterwards.
 */
public interface ProductAuthoring {

    /**
     * @param feeAccountId where the fee is credited. Null falls back to the caller-supplied account
     *     on the money path, which is the hole V4 exists to close — so the authoring surface
     *     requires it.
     */
    record FeeRule(
            String operation,
            String kind,
            Long flatMinor,
            Integer basisPoints,
            Long capMinor,
            String currency,
            UUID feeAccountId) {}

    record LimitRule(
            String kycTier, String channel, String limitType, long maxAmountMinor, String currency) {}

    /** One version and everything priced under it. */
    record VersionDetail(
            UUID productId,
            String productCode,
            String productType,
            int version,
            String status,
            String effectiveFrom,
            String publishedBy,
            List<FeeRule> feeRules,
            List<LimitRule> limitRules) {}

    /**
     * Creates the next version as a draft.
     *
     * @param copyFrom an existing version whose rules are copied into the new one, or null for an
     *     empty draft. Copying is the common case by a wide margin — a repricing changes one number
     *     and keeps the rest — and retyping the rest is how the rest gets changed by accident.
     * @param author the principal drafting it — the caller's own identity, never inferred. This
     *     used to be copied from the <em>previous</em> version's author, which quietly inverted
     *     maker-checker from version 2 on: if A wrote v1 and B drafted v2, the record said A wrote
     *     v2, so B could publish B's own pricing while A — who had touched nothing — was refused.
     *     {@code publisher_differs_from_author} can only hold the line when this field tells the
     *     truth.
     */
    int draftNextVersion(UUID tenantId, UUID productId, Integer copyFrom, String author);

    /** Replaces the draft's fee rules, as a set. */
    void setFeeRules(UUID tenantId, UUID productId, int version, List<FeeRule> rules);

    /** Replaces the draft's limit rules, as a set. */
    void setLimitRules(UUID tenantId, UUID productId, int version, List<LimitRule> rules);

    /** Schedules when a draft becomes live once published. */
    void setEffectiveFrom(UUID tenantId, UUID productId, int version, String effectiveFrom);

    /** One version with its rules, or null. */
    VersionDetail read(UUID tenantId, UUID productId, int version);

    /** No such product, or no such version of it, for this tenant. */
    class NoSuchVersion extends RuntimeException {
        public NoSuchVersion() {
            super("no such product version");
        }
    }

    /** The version is live, and a live version prices what already happened. */
    class VersionPublished extends RuntimeException {
        public final int version;

        public VersionPublished(int version) {
            super("version " + version + " is published and cannot be edited");
            this.version = version;
        }
    }

    /**
     * A rule set this version cannot hold — wrong shape, not wrong account.
     *
     * <p>Carries the {@code reason} and {@code details} the error contract requires (hard rule 9):
     * one code, {@code RULES_INVALID}, spans every way a rule can be malformed, and the reason is
     * how a caller tells a 500% fee from a channel the platform does not know.
     */
    class RulesInvalid extends RuntimeException {
        public final ProductErrorReason reason;
        public final java.util.Map<String, Object> details;

        public RulesInvalid(ProductErrorReason reason, java.util.Map<String, Object> details) {
            super("rules invalid: " + reason);
            this.reason = reason;
            this.details = details;
        }
    }

    /**
     * Two drafts of the same next version, at once.
     *
     * <p>{@code product_versions_unique} arbitrates the race; this is its verdict as a 409 rather
     * than a raw constraint violation surfacing as a 500. The remedy is to retry — the winner's
     * draft now exists and the next attempt drafts the version after it.
     */
    class DraftConflict extends RuntimeException {
        public DraftConflict() {
            super("another draft of this version was created concurrently — retry");
        }
    }
}
