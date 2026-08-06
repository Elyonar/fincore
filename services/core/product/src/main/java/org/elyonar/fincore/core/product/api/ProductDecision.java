package org.elyonar.fincore.core.product.api;

/**
 * The Product module's answer: permitted or not, at what fee, under which configuration.
 *
 * @param permitted whether the operation may proceed at all
 * @param feeMinor the fee to apply, in integer minor units. Zero is a valid answer.
 * @param limitMinor the applicable limit for the window, in minor units — what Orchestration
 *     reserves against. Zero when no limit applies.
 * @param productVersion the configuration version that produced this decision. Recorded on the
 *     saga so a past decision stays reconstructible after the configuration moves on.
 * @param refusal why not, when not permitted. Null when permitted.
 */
public record ProductDecision(
        boolean permitted, long feeMinor, long limitMinor, int productVersion, Refusal refusal) {

    /** Why an operation is refused. Each maps to a distinct API error code. */
    public enum Refusal {
        /** No such product, or no version in effect right now. */
        PRODUCT_NOT_FOUND,
        /** The product forbids this operation for this tier or channel. */
        OPERATION_NOT_PERMITTED,
        /** The amount alone exceeds the per-transaction limit. */
        LIMIT_EXCEEDED
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
    }

    public static ProductDecision permitted(long feeMinor, long limitMinor, int productVersion) {
        return new ProductDecision(true, feeMinor, limitMinor, productVersion, null);
    }

    public static ProductDecision refused(Refusal refusal, int productVersion) {
        return new ProductDecision(false, 0, 0, productVersion, refusal);
    }
}
