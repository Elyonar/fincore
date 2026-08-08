package org.elyonar.fincore.core.product.api;

import java.util.List;
import java.util.UUID;

/**
 * The question Lending asks Product: how does this loan product price, right now?
 *
 * <p>Read-only, like every cross-module port. The answer is the live published version's loan
 * rules; the caller pins {@code version} on the loan so the answer stays explicable after the
 * configuration moves on — the same discipline sagas apply to fees.
 */
public interface LoanProducts {

    /**
     * The live loan terms for this product code, or null when there is no published LOAN version
     * in effect — absent, another tenant's, unpublished and not-yet-effective all
     * indistinguishable, as everywhere.
     */
    LoanTerms termsFor(UUID tenantId, String productCode);

    /**
     * A published version's lending rules.
     *
     * @param allocationOrder repayment components in the order money reaches them
     * @param interestIncomeAccountId where recognized interest lands — configuration, not a
     *     caller assertion; null on versions predating the column
     */
    record LoanTerms(
            int version,
            int interestRateBp,
            String scheduleKind,
            long minAmountMinor,
            long maxAmountMinor,
            int minTermMonths,
            int maxTermMonths,
            int graceMonths,
            List<String> allocationOrder,
            UUID interestIncomeAccountId,
            int prepaymentFeeBp,
            String currency) {}
}
