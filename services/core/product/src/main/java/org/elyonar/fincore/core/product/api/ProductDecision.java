package org.elyonar.fincore.core.product.api;

import java.util.UUID;

/**
 * The Product module's answer: permitted or not, at what fee, into which account, under which
 * configuration.
 *
 * @param permitted whether the operation may proceed at all
 * @param feeMinor the fee to apply, in integer minor units. Zero is a valid answer.
 * @param feeAccountId the fee-income account this product's fee credits — configuration, not a
 *     caller assertion. Null when the fee rule predates the column (or there is no fee), in which
 *     case Orchestration's documented fallback applies.
 * @param limitMinor the applicable per-transaction limit for the window, in minor units — what
 *     Orchestration reserves against. Zero when no limit applies.
 * @param dailyLimitMinor the calendar-day limit for this tier and channel, or null when the
 *     product configures none. Enforced by Orchestration against the day's reservations —
 *     Product states the rule, Orchestration holds the running total.
 * @param productVersion the configuration version that produced this decision. Recorded on the
 *     saga so a past decision stays reconstructible after the configuration moves on.
 * @param refusal why not, when not permitted. Null when permitted.
 */
public record ProductDecision(
        boolean permitted,
        long feeMinor,
        UUID feeAccountId,
        long limitMinor,
        Long dailyLimitMinor,
        int productVersion,
        Refusal refusal) {

    /** Why an operation is refused. Each maps to a distinct API error code. */
    public enum Refusal {
        /** No such product, or no version in effect right now. */
        PRODUCT_NOT_FOUND,
        /** The product forbids this operation for this tier or channel. */
        OPERATION_NOT_PERMITTED,
        /** The amount alone exceeds the per-transaction limit. */
        LIMIT_EXCEEDED,
        /**
         * The operation is priced, but not in this currency.
         *
         * <p>A refusal rather than free: "absent ⇒ free" is the right reading only when the
         * operation has no fee rule at all. A version pricing a transfer in naira and asked about
         * one in dollars has an answer — it is just not configured yet — and waiving the fee until
         * somebody notices is a silent undercharge across every second currency.
         */
        CURRENCY_MISMATCH
    }

    public ProductDecision {
        // A fee is money, so the same rule applies here as everywhere: integer minor units,
        // never negative. A negative fee would be a credit nobody authorized.
        if (feeMinor < 0) {
            throw new IllegalArgumentException("feeMinor must not be negative");
        }
        if (limitMinor < 0) {
            throw new IllegalArgumentException("limitMinor must not be negative");
        }
        if (dailyLimitMinor != null && dailyLimitMinor < 0) {
            throw new IllegalArgumentException("dailyLimitMinor must not be negative");
        }
    }

    public static ProductDecision permitted(
            long feeMinor, UUID feeAccountId, long limitMinor, Long dailyLimitMinor, int productVersion) {
        return new ProductDecision(true, feeMinor, feeAccountId, limitMinor, dailyLimitMinor, productVersion, null);
    }

    public static ProductDecision refused(Refusal refusal, int productVersion) {
        return new ProductDecision(false, 0, null, 0, null, productVersion, refusal);
    }
}
