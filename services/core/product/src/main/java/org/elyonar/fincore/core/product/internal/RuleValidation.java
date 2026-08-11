package org.elyonar.fincore.core.product.internal;

import org.elyonar.fincore.core.product.api.ProductErrorReason;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.elyonar.fincore.core.product.api.LedgerAccounts;
import org.springframework.stereotype.Component;

/**
 * What a rule set has to be true of before it is stored.
 *
 * <p>Two kinds of check live here and they fail differently on purpose.
 *
 * <p><strong>Shape</strong> — a fee that is FLAT must carry an amount and no basis points, a term
 * range must not be inverted, a limit's tier and channel must be ones this platform knows. Most of
 * these are also database constraints, and that is deliberate: the constraint is what actually
 * holds, and this is what turns it into an answer an administrator can act on rather than a 500
 * carrying a Postgres message.
 *
 * <p><strong>Accounts</strong> — a rule that names where money lands is checked against the ledger
 * through {@link LedgerAccounts}. {@code V4__fee_account_configuration.sql} exists because a caller
 * could route a tenant's fee income to any account it could name; catching it at authoring time is
 * the only place it can be caught before a customer's money is involved. A ledger that cannot be
 * asked is never treated as an account that does not exist — that is {@link LedgerUnavailable}, a
 * 503, and the operator retries.
 *
 * <p>Every refusal is {@code RULES_INVALID} with a {@code reason} naming which rule failed and
 * {@code details} carrying the facts a message would interpolate. One code, several causes, a
 * reason to tell them apart — hard rule 9, and the reason the caller can render a sentence in a
 * language this service does not speak.
 */
@Component
public class RuleValidation {

    private static final Set<String> OPERATIONS = Set.of("DEPOSIT", "WITHDRAWAL", "TRANSFER");
    private static final Set<String> FEE_KINDS = Set.of("FLAT", "PERCENT");
    private static final Set<String> LIMIT_TYPES = Set.of("PER_TXN", "DAILY");
    private static final Set<String> SCHEDULE_KINDS = Set.of("ANNUITY", "FLAT", "BULLET");

    /**
     * The channel vocabulary, mirrored.
     *
     * <p>ADR 0012 makes channel a closed set gated by {@code channel:teller} and {@code channel:api}.
     * The set itself lives in Orchestration's {@code TransferController}, which Product may not
     * import. This mirror should stop being one when the vocabulary moves somewhere both modules may
     * read; until then a divergence here refuses a channel the money path would accept, which is the
     * safe direction to be wrong in.
     */
    private static final Set<String> CHANNELS = Set.of("TELLER", "API");

    /**
     * The KYC tier vocabulary, mirrored — and thinner than it looks.
     *
     * <p>{@code customer.customers.kyc_tier} is free text with no CHECK and no validation on the
     * write path, so the platform has no closed set to validate against. These three are the CBN
     * tiers the schema's own default ({@code 'TIER_1'}) comes from. Refusing anything else here is a
     * deliberate tightening: a limit rule for a tier no customer can hold is a limit that silently
     * never applies, which is worse than a refusal.
     */
    private static final Set<String> KYC_TIERS = Set.of("TIER_1", "TIER_2", "TIER_3");

    /** Where income may land. Not a customer's account, which is the whole point of the check. */
    private static final Set<String> INCOME_ACCOUNTS = Set.of("FEE", "INTERNAL");

    /** Where a disbursement may be funded from. */
    private static final Set<String> FUNDING_ACCOUNTS = Set.of("INTERNAL", "SETTLEMENT_MIRROR");

    private final LedgerAccounts accounts;

    public RuleValidation(LedgerAccounts accounts) {
        this.accounts = accounts;
    }

    // ---------------------------------------------------------------- fees

    public void checkFeeRules(UUID tenantId, List<ProductAuthoring.FeeRule> rules) {
        for (ProductAuthoring.FeeRule rule : rules) {
            if (!OPERATIONS.contains(rule.operation())) {
                throw invalid(ProductErrorReason.UNKNOWN_OPERATION, "operation", rule.operation(), "permitted", OPERATIONS);
            }
            if (!FEE_KINDS.contains(rule.kind())) {
                throw invalid(ProductErrorReason.UNKNOWN_FEE_BASIS, "kind", rule.kind(), "permitted", FEE_KINDS);
            }

            long flat = amount(rule.flatMinor(), "flatMinor");
            long cap = amount(rule.capMinor(), "capMinor");

            // The database says the same thing in `fee_shape`. This says it in a sentence.
            if ("FLAT".equals(rule.kind()) && (rule.flatMinor() == null || rule.basisPoints() != null)) {
                throw invalid(ProductErrorReason.UNKNOWN_FEE_BASIS, "kind", "FLAT", "expects", "flatMinor, and no basisPoints");
            }
            if ("PERCENT".equals(rule.kind()) && (rule.basisPoints() == null || rule.flatMinor() != null)) {
                throw invalid(ProductErrorReason.UNKNOWN_FEE_BASIS, "kind", "PERCENT", "expects", "basisPoints, and no flatMinor");
            }
            if (rule.basisPoints() != null && (rule.basisPoints() < 0 || rule.basisPoints() > 10_000)) {
                // 10,000 basis points is 100%. A fee above the amount is a typed zero too many.
                throw invalid(ProductErrorReason.RATE_OUT_OF_RANGE, "basisPoints", rule.basisPoints(), "max", 10_000);
            }
            if (flat < 0 || cap < 0) {
                throw invalid(ProductErrorReason.BOUNDS_INVERTED, "field", "flatMinor/capMinor", "reason", "negative");
            }

            requireCurrency(rule.currency());
            requireAccount(tenantId, rule.feeAccountId(), INCOME_ACCOUNTS, rule.currency(), "feeAccountId");
        }
    }

    // ---------------------------------------------------------------- limits

    public void checkLimitRules(UUID tenantId, List<ProductAuthoring.LimitRule> rules) {
        for (ProductAuthoring.LimitRule rule : rules) {
            if (!KYC_TIERS.contains(rule.kycTier())) {
                throw invalid(ProductErrorReason.UNKNOWN_KYC_TIER, "kycTier", rule.kycTier(), "permitted", KYC_TIERS);
            }
            if (!CHANNELS.contains(rule.channel())) {
                throw invalid(ProductErrorReason.UNKNOWN_CHANNEL, "channel", rule.channel(), "permitted", CHANNELS);
            }
            if (!LIMIT_TYPES.contains(rule.limitType())) {
                throw invalid(ProductErrorReason.UNKNOWN_LIMIT_TYPE, "limitType", rule.limitType(), "permitted", LIMIT_TYPES);
            }
            if (amount(rule.maxAmountMinor(), "maxAmountMinor") <= 0) {
                throw invalid(ProductErrorReason.BOUNDS_INVERTED, "field", "maxAmountMinor", "reason", "must be above zero");
            }
            requireCurrency(rule.currency());
        }
    }

    // ---------------------------------------------------------------- loans

    public void checkLoanRule(UUID tenantId, ProductAuthoring.LoanRule rule) {
        if (rule == null) {
            return;
        }
        if (!SCHEDULE_KINDS.contains(rule.scheduleKind())) {
            throw invalid(ProductErrorReason.UNKNOWN_SCHEDULE_KIND, "scheduleKind", rule.scheduleKind(), "permitted", SCHEDULE_KINDS);
        }
        if (rule.interestRateBp() < 0 || rule.interestRateBp() > 100_000) {
            // 100,000 bp is 1,000% — absurd, and still generous enough not to argue with a
            // microfinance rate expressed per annum.
            throw invalid(ProductErrorReason.RATE_OUT_OF_RANGE, "interestRateBp", rule.interestRateBp(), "max", 100_000);
        }
        if (rule.prepaymentFeeBp() < 0 || rule.prepaymentFeeBp() > 10_000) {
            throw invalid(ProductErrorReason.RATE_OUT_OF_RANGE, "prepaymentFeeBp", rule.prepaymentFeeBp(), "max", 10_000);
        }
        if (rule.penaltyRateBp() < 0 || rule.penaltyRateBp() > 10_000) {
            throw invalid(ProductErrorReason.RATE_OUT_OF_RANGE, "penaltyRateBp", rule.penaltyRateBp(), "max", 10_000);
        }

        long min = amount(rule.minAmountMinor(), "minAmountMinor");
        long max = amount(rule.maxAmountMinor(), "maxAmountMinor");
        if (min <= 0) {
            throw invalid(ProductErrorReason.BOUNDS_INVERTED, "field", "minAmountMinor", "reason", "must be above zero");
        }
        if (max < min) {
            throw invalid(ProductErrorReason.BOUNDS_INVERTED, "minAmountMinor", rule.minAmountMinor(), "maxAmountMinor", rule.maxAmountMinor());
        }
        if (rule.minTermMonths() <= 0) {
            throw invalid(ProductErrorReason.BOUNDS_INVERTED, "field", "minTermMonths", "reason", "must be above zero");
        }
        if (rule.maxTermMonths() < rule.minTermMonths()) {
            throw invalid(ProductErrorReason.BOUNDS_INVERTED, "minTermMonths", rule.minTermMonths(), "maxTermMonths", rule.maxTermMonths());
        }
        if (rule.graceMonths() < 0) {
            throw invalid(ProductErrorReason.BOUNDS_INVERTED, "field", "graceMonths", "reason", "negative");
        }
        if (amount(rule.penaltyFlatMinor(), "penaltyFlatMinor") < 0
                || amount(rule.penaltyCapMinor(), "penaltyCapMinor") < 0) {
            throw invalid(ProductErrorReason.BOUNDS_INVERTED, "field", "penaltyFlatMinor/penaltyCapMinor", "reason", "negative");
        }

        requireCurrency(rule.currency());
        requireAccount(tenantId, rule.interestIncomeAccountId(), INCOME_ACCOUNTS, rule.currency(), "interestIncomeAccountId");
        requireAccount(tenantId, rule.penaltyIncomeAccountId(), INCOME_ACCOUNTS, rule.currency(), "penaltyIncomeAccountId");
        requireAccount(tenantId, rule.fundingAccountId(), FUNDING_ACCOUNTS, rule.currency(), "fundingAccountId");
    }

    // ---------------------------------------------------------------- effective dating

    /**
     * A draft may be dated forward, never backward.
     *
     * <p>Backdating configuration would make a completed transaction's decision unreconstructible:
     * the saga records which version priced it, and a version that claims to have been live before
     * it existed turns that record into a lie.
     */
    public void checkEffectiveFrom(OffsetDateTime effectiveFrom) {
        if (effectiveFrom == null) {
            throw invalid(ProductErrorReason.EFFECTIVE_FROM_INVALID, "effectiveFrom", null, "reason", "required");
        }
        if (effectiveFrom.isBefore(OffsetDateTime.now())) {
            throw new EffectiveFromInThePast();
        }
    }

    // ---------------------------------------------------------------- shared

    private void requireCurrency(String currency) {
        if (currency == null || currency.length() != 3 || !currency.equals(currency.toUpperCase())) {
            throw invalid(ProductErrorReason.CURRENCY_INVALID, "currency", currency, "expects", "an uppercase ISO 4217 code");
        }
    }

    /**
     * Checks one named account, when one is named.
     *
     * <p>Null is permitted throughout: every account column is nullable, and a version that names no
     * fee account falls back to the caller-supplied one exactly as it does today. Refusing null here
     * would break every existing published version's shape on its first re-draft.
     */
    private void requireAccount(
            UUID tenantId, UUID accountId, Set<String> permitted, String currency, String field) {
        if (accountId == null) {
            return;
        }

        LedgerAccounts.Account account = accounts.describe(tenantId, accountId);

        if (account instanceof LedgerAccounts.Account.Unreadable unreadable) {
            throw new LedgerUnavailable(unreadable.reason());
        }
        if (account instanceof LedgerAccounts.Account.Absent) {
            throw invalid(ProductErrorReason.ACCOUNT_NOT_FOUND, "field", field, "accountId", accountId);
        }

        LedgerAccounts.Account.Known known = (LedgerAccounts.Account.Known) account;
        if (!permitted.contains(known.type())) {
            // The failure this check exists for: a fee-income account that is really somebody's
            // savings account routes the tenant's revenue into it, and nothing downstream notices.
            throw invalid(ProductErrorReason.ACCOUNT_WRONG_TYPE, "field", field, "type", known.type(), "permitted", permitted);
        }
        if (!known.isOpen()) {
            throw invalid(ProductErrorReason.ACCOUNT_WRONG_TYPE, "field", field, "status", known.status());
        }
        if (currency != null && !currency.equals(known.currency())) {
            throw invalid(ProductErrorReason.ACCOUNT_WRONG_TYPE, "field", field, "currency", known.currency(), "expected", currency);
        }
    }

    /** Minor units arrive as decimal strings. A number that will not parse is a refusal, not a 500. */
    private long amount(String wire, String field) {
        if (wire == null) {
            return 0L;
        }
        try {
            return Long.parseLong(wire);
        } catch (NumberFormatException e) {
            throw invalid(ProductErrorReason.AMOUNT_MALFORMED, "field", field, "supplied", wire);
        }
    }

    private static RulesInvalid invalid(ProductErrorReason reason, Object... details) {
        Map<String, Object> facts = new LinkedHashMap<>();
        for (int i = 0; i + 1 < details.length; i += 2) {
            facts.put(String.valueOf(details[i]), details[i + 1] == null ? null : String.valueOf(details[i + 1]));
        }
        return new RulesInvalid(reason.name(), facts);
    }

    // ---------------------------------------------------------------- refusals

    /** One code, several causes, a reason to tell them apart, and the facts a message interpolates. */
    public static class RulesInvalid extends RuntimeException {
        private final String reason;
        private final transient Map<String, Object> details;

        public RulesInvalid(String reason, Map<String, Object> details) {
            super("rules invalid: " + reason);
            this.reason = reason;
            this.details = details;
        }

        public String reason() {
            return reason;
        }

        public Map<String, Object> details() {
            return details;
        }
    }

    /** Dating a version into the past. Its own code, because the fix is different from a bad rule. */
    public static class EffectiveFromInThePast extends RuntimeException {
        public EffectiveFromInThePast() {
            super("a version cannot become effective before it existed");
        }
    }

    /**
     * The ledger could not be asked whether a named account is usable.
     *
     * <p>A 503, never a refusal of the rule: the operator's configuration may be perfectly correct
     * and the answer simply unavailable. Reporting "no such account" here would be the read-side
     * version of compensating an unknown outcome.
     */
    public static class LedgerUnavailable extends RuntimeException {
        public LedgerUnavailable(String reason) {
            super("could not verify the named accounts: " + reason);
        }
    }
}
