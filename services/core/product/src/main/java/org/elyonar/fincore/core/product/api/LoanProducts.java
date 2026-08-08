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
     * @param penaltyFlatMinor charged once per late installment (v1.17); zero means none
     * @param penaltyRateBp basis points <em>per day</em> on overdue principal (v1.17)
     * @param penaltyCapMinor lifetime cap on penalties charged per loan; null means uncapped
     * @param penaltyIncomeAccountId where recognized penalties land; null falls back to
     *     {@code interestIncomeAccountId}
     * @param fundingAccountId the tenant's loan funding account, configuration-first on
     *     disburse; null on versions predating the column (caller-supplied fallback applies)
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
            String currency,
            long penaltyFlatMinor,
            int penaltyRateBp,
            Long penaltyCapMinor,
            UUID penaltyIncomeAccountId,
            UUID fundingAccountId) {}
}
