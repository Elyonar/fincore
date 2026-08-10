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
     *
     * <p>For deciding what a <em>new</em> application is offered, and nothing else. Every later
     * question about an existing application or loan must ask {@link #termsForVersion} with the
     * version that was pinned when it was written — see there for why.
     */
    LoanTerms termsFor(UUID tenantId, String productCode);

    /**
     * The terms of one specific version, published or not.
     *
     * <p>This exists because every consumer used the live lookup, and a loan stores the version it
     * was written under precisely so that it need not. While a product could be versioned only
     * once the two were the same query; now that an institution can publish version 2, they are
     * not — and reading live would mean a new penalty rate silently repricing every loan already
     * on the books, including the interest already accrued on them.
     *
     * <p>Not filtered by {@code status} or {@code effective_from}: a loan written under version 3
     * is explained by version 3 forever, including after version 4 supersedes it. That is the whole
     * point of pinning.
     */
    LoanTerms termsForVersion(UUID tenantId, String productCode, int version);

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
