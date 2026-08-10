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

    record LoanRule(
            int interestRateBp,
            String scheduleKind,
            long minAmountMinor,
            long maxAmountMinor,
            int minTermMonths,
            int maxTermMonths,
            int graceMonths,
            String allocationOrder,
            int prepaymentFeeBp,
            long penaltyFlatMinor,
            int penaltyRateBp,
            Long penaltyCapMinor,
            String currency,
            UUID interestIncomeAccountId,
            UUID penaltyIncomeAccountId,
            UUID fundingAccountId) {}

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
            List<LimitRule> limitRules,
            LoanRule loanRule) {}

    /**
     * Creates the next version as a draft.
     *
     * @param copyFrom an existing version whose rules are copied into the new one, or null for an
     *     empty draft. Copying is the common case by a wide margin — a repricing changes one number
     *     and keeps the rest — and retyping the rest is how the rest gets changed by accident.
     */
    int draftNextVersion(UUID tenantId, UUID productId, Integer copyFrom);

    /** Replaces the draft's fee rules, as a set. */
    void setFeeRules(UUID tenantId, UUID productId, int version, List<FeeRule> rules);

    /** Replaces the draft's limit rules, as a set. */
    void setLimitRules(UUID tenantId, UUID productId, int version, List<LimitRule> rules);

    /** Replaces the draft's loan terms. One rule set per version: a version prices one way. */
    void setLoanRule(UUID tenantId, UUID productId, int version, LoanRule rule);

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
}
