package org.elyonar.fincore.product.api;

/**
 * What Orchestration is allowed to ask the Product module.
 *
 * <p><strong>Product returns decisions, never postings.</strong> It answers "is this permitted,
 * what fee applies, what limit applies, under which configuration version" and Orchestration turns
 * that answer into ledger entries. The split matters because it keeps rule evaluation testable
 * without a ledger, and keeps entry construction in the one module allowed to talk to one.
 */
public interface ProductDecisions {

    /**
     * Evaluates an intended operation against the product configuration live right now.
     *
     * <p>The returned decision carries the configuration version that produced it, and
     * Orchestration records that version on the saga. A completed transaction has to remain
     * explicable after the configuration changes — an examiner asking why a fee was ₦20 a year ago
     * needs the answer, not today's rules.
     */
    ProductDecision evaluate(ProductRequest request);
}
