package org.elyonar.fincore.core.product.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drafting versions and authoring the rules that price them.
 *
 * <p>Separate from {@link ProductRecords} because that class holds the catalogue and the act of
 * publishing, and this one holds the writes that only ever touch a draft. Both are administrative
 * and neither belongs in {@code product.api} — that package is the contract Orchestration evaluates
 * fees and limits through, and widening it to include authoring would make the money path depend on
 * the shape of an admin console.
 *
 * <p>Two rules govern everything here.
 *
 * <p><strong>A published version is never edited.</strong> Every write below locates the version
 * first and refuses unless it is a DRAFT. The database refuses too — {@code
 * V7__published_rules_are_immutable.sql} put a trigger on all three rule tables — and the database
 * is the one that actually holds the line. These checks exist to produce a decent error instead of
 * a constraint violation, exactly as {@code ProductRecords.publish} does for maker-checker.
 *
 * <p><strong>Rules are replaced as a set, never patched row by row.</strong> A fee schedule is a
 * coherent object; PATCHing one rule out of four invites a version that prices half of what its
 * author intended. Each replace is total and idempotent: send the rules the version should have,
 * and what was there before is gone.
 */
@Repository
public class ProductAuthoring {

    private final JdbcTemplate jdbc;

    public ProductAuthoring(@Qualifier("productJdbcTemplate") JdbcTemplate productJdbcTemplate) {
        this.jdbc = productJdbcTemplate;
    }

    private void scopeTo(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    /**
     * Drafts the next version of a product, optionally copying an existing one's rules.
     *
     * <p>Cloning is the common case and the reason this takes a source at all: a price change is
     * almost always "last version, but the transfer fee is 150" and retyping the other nine rules
     * is how a version ends up differing in a way nobody intended. The source may be any version of
     * this product, draft or published — a published one because that is what is live, a draft one
     * because an author may want to branch from work in progress.
     *
     * <p>The new version is always {@code max(version) + 1}. Nothing lets a caller choose the
     * number: versions order history, and a caller-chosen number is a caller-chosen history.
     */
    @Transactional(transactionManager = "productTransactionManager")
    public ProductRecords.Version draft(UUID tenantId, UUID productId, Integer cloneFrom, String createdBy) {
        scopeTo(tenantId);
        requireProduct(productId);

        UUID source = cloneFrom == null ? null : versionId(productId, cloneFrom);

        Integer highest =
                jdbc.queryForObject(
                        "SELECT max(version) FROM product.product_versions WHERE product_id = ?",
                        Integer.class, productId);
        int next = (highest == null ? 0 : highest) + 1;

        UUID created =
                jdbc.queryForObject(
                        """
                        INSERT INTO product.product_versions
                            (tenant_id, product_id, version, status, created_by)
                        VALUES (?,?,?,'DRAFT',?)
                        RETURNING id
                        """,
                        UUID.class, tenantId, productId, next, createdBy);

        if (source != null) {
            copyRules(tenantId, source, created);
        }

        return readHeader(productId, next);
    }

    /** Copies one version's rules onto another. Column lists are explicit so a new column fails loudly. */
    private void copyRules(UUID tenantId, UUID source, UUID target) {
        jdbc.update(
                """
                INSERT INTO product.fee_rules
                    (tenant_id, product_version_id, operation, kind, flat_minor, basis_points,
                     cap_minor, currency, fee_account_id)
                SELECT ?, ?, operation, kind, flat_minor, basis_points, cap_minor, currency, fee_account_id
                  FROM product.fee_rules WHERE product_version_id = ?
                """,
                tenantId, target, source);

        jdbc.update(
                """
                INSERT INTO product.limit_rules
                    (tenant_id, product_version_id, kyc_tier, channel, limit_type, max_amount_minor, currency)
                SELECT ?, ?, kyc_tier, channel, limit_type, max_amount_minor, currency
                  FROM product.limit_rules WHERE product_version_id = ?
                """,
                tenantId, target, source);

        jdbc.update(
                """
                INSERT INTO product.loan_rules
                    (tenant_id, product_version_id, interest_rate_bp, schedule_kind, min_amount_minor,
                     max_amount_minor, min_term_months, max_term_months, grace_months, allocation_order,
                     interest_income_account_id, prepayment_fee_bp, currency, penalty_flat_minor,
                     penalty_rate_bp, penalty_cap_minor, penalty_income_account_id, funding_account_id)
                SELECT ?, ?, interest_rate_bp, schedule_kind, min_amount_minor,
                       max_amount_minor, min_term_months, max_term_months, grace_months, allocation_order,
                       interest_income_account_id, prepayment_fee_bp, currency, penalty_flat_minor,
                       penalty_rate_bp, penalty_cap_minor, penalty_income_account_id, funding_account_id
                  FROM product.loan_rules WHERE product_version_id = ?
                """,
                tenantId, target, source);
    }

    /** One version with every rule that prices it — the read an edit form and a review both need. */
    @Transactional(readOnly = true, transactionManager = "productTransactionManager")
    public VersionDetail read(UUID tenantId, UUID productId, int version) {
        scopeTo(tenantId);
        requireProduct(productId);
        UUID id = versionId(productId, version);
        ProductRecords.Version header = readHeader(productId, version);

        List<FeeRule> fees =
                jdbc.query(
                        """
                        SELECT operation, kind, flat_minor, basis_points, cap_minor, currency, fee_account_id
                          FROM product.fee_rules WHERE product_version_id = ? ORDER BY operation
                        """,
                        (rs, row) ->
                                new FeeRule(
                                        rs.getString("operation"),
                                        rs.getString("kind"),
                                        wire(rs.getObject("flat_minor", Long.class)),
                                        rs.getObject("basis_points", Integer.class),
                                        wire(rs.getObject("cap_minor", Long.class)),
                                        rs.getString("currency").trim(),
                                        rs.getObject("fee_account_id", UUID.class)),
                        id);

        List<LimitRule> limits =
                jdbc.query(
                        """
                        SELECT kyc_tier, channel, limit_type, max_amount_minor, currency
                          FROM product.limit_rules WHERE product_version_id = ?
                         ORDER BY kyc_tier, channel, limit_type
                        """,
                        (rs, row) ->
                                new LimitRule(
                                        rs.getString("kyc_tier"),
                                        rs.getString("channel"),
                                        rs.getString("limit_type"),
                                        wire(rs.getLong("max_amount_minor")),
                                        rs.getString("currency").trim()),
                        id);

        LoanRule loan =
                jdbc.query(
                        """
                        SELECT interest_rate_bp, schedule_kind, min_amount_minor, max_amount_minor,
                               min_term_months, max_term_months, grace_months, allocation_order,
                               interest_income_account_id, prepayment_fee_bp, currency,
                               penalty_flat_minor, penalty_rate_bp, penalty_cap_minor,
                               penalty_income_account_id, funding_account_id
                          FROM product.loan_rules WHERE product_version_id = ?
                        """,
                        rs ->
                                rs.next()
                                        ? new LoanRule(
                                                rs.getInt("interest_rate_bp"),
                                                rs.getString("schedule_kind"),
                                                wire(rs.getLong("min_amount_minor")),
                                                wire(rs.getLong("max_amount_minor")),
                                                rs.getInt("min_term_months"),
                                                rs.getInt("max_term_months"),
                                                rs.getInt("grace_months"),
                                                rs.getString("allocation_order"),
                                                rs.getObject("interest_income_account_id", UUID.class),
                                                rs.getInt("prepayment_fee_bp"),
                                                rs.getString("currency").trim(),
                                                wire(rs.getLong("penalty_flat_minor")),
                                                rs.getInt("penalty_rate_bp"),
                                                wire(rs.getObject("penalty_cap_minor", Long.class)),
                                                rs.getObject("penalty_income_account_id", UUID.class),
                                                rs.getObject("funding_account_id", UUID.class))
                                        : null,
                        id);

        return new VersionDetail(
                header.version(),
                header.status(),
                header.effectiveFrom(),
                header.createdBy(),
                header.publishedBy(),
                fees,
                limits,
                loan);
    }

    /** Replaces the fee schedule of a draft, wholesale. */
    @Transactional(transactionManager = "productTransactionManager")
    public VersionDetail replaceFeeRules(UUID tenantId, UUID productId, int version, List<FeeRule> rules) {
        scopeTo(tenantId);
        UUID id = requireDraft(productId, version);

        jdbc.update("DELETE FROM product.fee_rules WHERE product_version_id = ?", id);
        for (FeeRule rule : rules) {
            jdbc.update(
                    """
                    INSERT INTO product.fee_rules
                        (tenant_id, product_version_id, operation, kind, flat_minor, basis_points,
                         cap_minor, currency, fee_account_id)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """,
                    tenantId,
                    id,
                    rule.operation(),
                    rule.kind(),
                    minor(rule.flatMinor()),
                    rule.basisPoints(),
                    minor(rule.capMinor()),
                    rule.currency(),
                    rule.feeAccountId());
        }
        return read(tenantId, productId, version);
    }

    /** Replaces the limit schedule of a draft, wholesale. */
    @Transactional(transactionManager = "productTransactionManager")
    public VersionDetail replaceLimitRules(UUID tenantId, UUID productId, int version, List<LimitRule> rules) {
        scopeTo(tenantId);
        UUID id = requireDraft(productId, version);

        jdbc.update("DELETE FROM product.limit_rules WHERE product_version_id = ?", id);
        for (LimitRule rule : rules) {
            jdbc.update(
                    """
                    INSERT INTO product.limit_rules
                        (tenant_id, product_version_id, kyc_tier, channel, limit_type,
                         max_amount_minor, currency)
                    VALUES (?,?,?,?,?,?,?)
                    """,
                    tenantId,
                    id,
                    rule.kycTier(),
                    rule.channel(),
                    rule.limitType(),
                    minor(rule.maxAmountMinor()),
                    rule.currency());
        }
        return read(tenantId, productId, version);
    }

    /**
     * Replaces the loan terms of a draft.
     *
     * <p>One rule set per version — {@code one_loan_rule_per_version} says a version prices one way
     * — so this is a single object rather than a list, and a null clears it.
     */
    @Transactional(transactionManager = "productTransactionManager")
    public VersionDetail replaceLoanRules(UUID tenantId, UUID productId, int version, LoanRule rule) {
        scopeTo(tenantId);
        UUID id = requireDraft(productId, version);

        if (!"LOAN".equals(productType(productId))) {
            throw new LoanRulesOnNonLoanProduct();
        }

        jdbc.update("DELETE FROM product.loan_rules WHERE product_version_id = ?", id);
        if (rule != null) {
            jdbc.update(
                    """
                    INSERT INTO product.loan_rules
                        (tenant_id, product_version_id, interest_rate_bp, schedule_kind, min_amount_minor,
                         max_amount_minor, min_term_months, max_term_months, grace_months, allocation_order,
                         interest_income_account_id, prepayment_fee_bp, currency, penalty_flat_minor,
                         penalty_rate_bp, penalty_cap_minor, penalty_income_account_id, funding_account_id)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    tenantId,
                    id,
                    rule.interestRateBp(),
                    rule.scheduleKind(),
                    minor(rule.minAmountMinor()),
                    minor(rule.maxAmountMinor()),
                    rule.minTermMonths(),
                    rule.maxTermMonths(),
                    rule.graceMonths(),
                    rule.allocationOrder(),
                    rule.interestIncomeAccountId(),
                    rule.prepaymentFeeBp(),
                    rule.currency(),
                    minor(rule.penaltyFlatMinor()),
                    rule.penaltyRateBp(),
                    minor(rule.penaltyCapMinor()),
                    rule.penaltyIncomeAccountId(),
                    rule.fundingAccountId());
        }
        return read(tenantId, productId, version);
    }

    /**
     * Sets when a draft becomes live once published.
     *
     * <p>The column has existed and been unreachable since V2. PRD §4.3 requires effective-dated
     * configuration, and without this a version goes live the instant somebody publishes it —
     * which is not how a bank changes a fee.
     */
    @Transactional(transactionManager = "productTransactionManager")
    public VersionDetail setEffectiveFrom(
            UUID tenantId, UUID productId, int version, OffsetDateTime effectiveFrom) {
        scopeTo(tenantId);
        requireDraft(productId, version);

        jdbc.update(
                "UPDATE product.product_versions SET effective_from = ? WHERE product_id = ? AND version = ?",
                effectiveFrom, productId, version);

        return read(tenantId, productId, version);
    }

    // ---------------------------------------------------------------- locating and refusing

    private void requireProduct(UUID productId) {
        Integer found =
                jdbc.queryForObject(
                        "SELECT count(*) FROM product.products WHERE id = ?", Integer.class, productId);
        if (found == null || found == 0) {
            // Another tenant's product is invisible under RLS and lands here, deliberately
            // indistinguishable from one that never existed.
            throw new ProductRecords.NoSuchProduct();
        }
    }

    private String productType(UUID productId) {
        return jdbc.queryForObject("SELECT type FROM product.products WHERE id = ?", String.class, productId);
    }

    private UUID versionId(UUID productId, int version) {
        UUID id =
                jdbc.query(
                        "SELECT id FROM product.product_versions WHERE product_id = ? AND version = ?",
                        rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                        productId, version);
        if (id == null) {
            throw new ProductRecords.NoSuchVersion();
        }
        return id;
    }

    /** The version's id, or a refusal. Every write on this class goes through here. */
    private UUID requireDraft(UUID productId, int version) {
        requireProduct(productId);
        String[] found =
                jdbc.query(
                        """
                        SELECT id::text, status FROM product.product_versions
                         WHERE product_id = ? AND version = ?
                        """,
                        rs -> rs.next() ? new String[] {rs.getString(1), rs.getString(2)} : null,
                        productId, version);
        if (found == null) {
            throw new ProductRecords.NoSuchVersion();
        }
        if (!"DRAFT".equals(found[1])) {
            throw new VersionNotDraft();
        }
        return UUID.fromString(found[0]);
    }

    private ProductRecords.Version readHeader(UUID productId, int version) {
        return jdbc.queryForObject(
                """
                SELECT version, status, effective_from, created_by, published_by
                  FROM product.product_versions WHERE product_id = ? AND version = ?
                """,
                (rs, row) ->
                        new ProductRecords.Version(
                                rs.getInt("version"),
                                rs.getString("status"),
                                rs.getObject("effective_from", OffsetDateTime.class),
                                rs.getString("created_by"),
                                rs.getString("published_by")),
                productId, version);
    }

    // ---------------------------------------------------------------- money on the wire

    /**
     * Minor units cross this boundary as decimal strings, never as JSON numbers.
     *
     * <p>The platform rule, and not a stylistic one: a kobo amount past 2^53 stops being exact in a
     * JavaScript client, and the ledger already answers this way. Parsing is
     * {@link RuleValidation}'s job; by the time a value reaches here it is a string that parsed.
     */
    private static Long minor(String wire) {
        return wire == null ? null : Long.valueOf(wire);
    }

    private static String wire(Long minor) {
        return minor == null ? null : Long.toString(minor);
    }

    // ---------------------------------------------------------------- the authored shapes

    /** A fee for one operation. Monetary fields are decimal strings; {@code basisPoints} is not money. */
    public record FeeRule(
            String operation,
            String kind,
            String flatMinor,
            Integer basisPoints,
            String capMinor,
            String currency,
            UUID feeAccountId) {}

    /** A ceiling for one KYC tier on one channel. */
    public record LimitRule(
            String kycTier, String channel, String limitType, String maxAmountMinor, String currency) {}

    /** How a version lends: rate, shape, bounds, allocation, penalties and the accounts involved. */
    public record LoanRule(
            int interestRateBp,
            String scheduleKind,
            String minAmountMinor,
            String maxAmountMinor,
            int minTermMonths,
            int maxTermMonths,
            int graceMonths,
            String allocationOrder,
            UUID interestIncomeAccountId,
            int prepaymentFeeBp,
            String currency,
            String penaltyFlatMinor,
            int penaltyRateBp,
            String penaltyCapMinor,
            UUID penaltyIncomeAccountId,
            UUID fundingAccountId) {}

    /** A version and everything that prices it. {@code loanRule} is null for a non-loan product. */
    public record VersionDetail(
            int version,
            String status,
            OffsetDateTime effectiveFrom,
            String createdBy,
            String publishedBy,
            List<FeeRule> feeRules,
            List<LimitRule> limitRules,
            LoanRule loanRule) {}

    // ---------------------------------------------------------------- refusals

    /** Any write against a version that is already live. */
    public static class VersionNotDraft extends RuntimeException {
        public VersionNotDraft() {
            super("that version is published; draft a new one");
        }
    }

    /** Loan terms on a savings or current product. */
    public static class LoanRulesOnNonLoanProduct extends RuntimeException {
        public LoanRulesOnNonLoanProduct() {
            super("loan terms belong to a LOAN product");
        }
    }
}
