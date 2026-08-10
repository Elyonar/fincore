package org.elyonar.fincore.core.product.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.core.product.api.ProductAuthoring;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writing pricing.
 *
 * <p>The rule this class exists to hold is the one {@link ProductRecords} already holds for
 * versions: <strong>a published version is never edited</strong>. Every method here resolves the
 * version first and refuses if it is live. The database refuses too — V7 put the same guard on all
 * three rule tables — and the check here exists to produce a decent error rather than a constraint
 * violation, exactly as publishing does.
 *
 * <p>Rules are replaced as a set rather than patched row by row. A fee schedule is a whole thing:
 * "the deposit fee" and "the withdrawal fee" are read together by the evaluator, and an interface
 * that lets one be edited while the other is stale invites a version that prices half of what its
 * author intended.
 */
@Repository
public class ProductAuthoringRecords implements ProductAuthoring {

    private final JdbcTemplate jdbc;

    public ProductAuthoringRecords(@Qualifier("productJdbcTemplate") JdbcTemplate productJdbcTemplate) {
        this.jdbc = productJdbcTemplate;
    }

    private void scopeTo(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    /** The version row's id, refusing when it is absent or live. */
    private UUID draftVersionId(UUID tenantId, UUID productId, int version) {
        String[] found = jdbc.query(
                "SELECT id::text, status FROM product.product_versions"
                        + " WHERE tenant_id = ? AND product_id = ? AND version = ?",
                rs -> rs.next() ? new String[] {rs.getString(1), rs.getString(2)} : null,
                tenantId,
                productId,
                version);
        if (found == null) {
            throw new NoSuchVersion();
        }
        if ("PUBLISHED".equals(found[1])) {
            throw new VersionPublished(version);
        }
        return UUID.fromString(found[0]);
    }

    @Override
    @Transactional(transactionManager = "productTransactionManager")
    public int draftNextVersion(UUID tenantId, UUID productId, Integer copyFrom) {
        scopeTo(tenantId);

        Integer highest = jdbc.queryForObject(
                "SELECT max(version) FROM product.product_versions WHERE tenant_id = ? AND product_id = ?",
                Integer.class,
                tenantId,
                productId);
        if (highest == null) {
            // No versions at all means no product, since creation makes both in one transaction.
            throw new NoSuchVersion();
        }
        int next = highest + 1;

        UUID newVersionId = jdbc.queryForObject(
                "INSERT INTO product.product_versions (tenant_id, product_id, version, status, created_by)"
                        + " VALUES (?,?,?,'DRAFT',?) RETURNING id",
                UUID.class,
                tenantId,
                productId,
                next,
                // Attribution matters at publish, where maker-checker compares it. A draft's author
                // is recorded here so that comparison has something to compare against.
                authorOf(tenantId, productId, highest));

        if (copyFrom != null) {
            UUID source = jdbc.query(
                    "SELECT id FROM product.product_versions"
                            + " WHERE tenant_id = ? AND product_id = ? AND version = ?",
                    rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                    tenantId,
                    productId,
                    copyFrom);
            if (source == null) {
                throw new NoSuchVersion();
            }
            copyRules(tenantId, source, newVersionId);
        }
        return next;
    }

    /** Who drafted the version we are following. Falls back to the platform when there is nobody. */
    private String authorOf(UUID tenantId, UUID productId, int version) {
        String author = jdbc.query(
                "SELECT created_by FROM product.product_versions"
                        + " WHERE tenant_id = ? AND product_id = ? AND version = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                tenantId,
                productId,
                version);
        return author == null ? "system:core" : author;
    }

    private void copyRules(UUID tenantId, UUID sourceVersionId, UUID targetVersionId) {
        jdbc.update(
                "INSERT INTO product.fee_rules"
                        + " (tenant_id, product_version_id, operation, kind, flat_minor, basis_points,"
                        + "  cap_minor, currency, fee_account_id)"
                        + " SELECT tenant_id, ?, operation, kind, flat_minor, basis_points,"
                        + "        cap_minor, currency, fee_account_id"
                        + "   FROM product.fee_rules WHERE tenant_id = ? AND product_version_id = ?",
                targetVersionId,
                tenantId,
                sourceVersionId);
        jdbc.update(
                "INSERT INTO product.limit_rules"
                        + " (tenant_id, product_version_id, kyc_tier, channel, limit_type,"
                        + "  max_amount_minor, currency)"
                        + " SELECT tenant_id, ?, kyc_tier, channel, limit_type, max_amount_minor, currency"
                        + "   FROM product.limit_rules WHERE tenant_id = ? AND product_version_id = ?",
                targetVersionId,
                tenantId,
                sourceVersionId);
        jdbc.update(
                "INSERT INTO product.loan_rules"
                        + " (tenant_id, product_version_id, interest_rate_bp, schedule_kind,"
                        + "  min_amount_minor, max_amount_minor, min_term_months, max_term_months,"
                        + "  grace_months, allocation_order, interest_income_account_id, prepayment_fee_bp,"
                        + "  currency, penalty_flat_minor, penalty_rate_bp, penalty_cap_minor,"
                        + "  penalty_income_account_id, funding_account_id)"
                        + " SELECT tenant_id, ?, interest_rate_bp, schedule_kind,"
                        + "        min_amount_minor, max_amount_minor, min_term_months, max_term_months,"
                        + "        grace_months, allocation_order, interest_income_account_id, prepayment_fee_bp,"
                        + "        currency, penalty_flat_minor, penalty_rate_bp, penalty_cap_minor,"
                        + "        penalty_income_account_id, funding_account_id"
                        + "   FROM product.loan_rules WHERE tenant_id = ? AND product_version_id = ?",
                targetVersionId,
                tenantId,
                sourceVersionId);
    }

    @Override
    @Transactional(transactionManager = "productTransactionManager")
    public void setFeeRules(UUID tenantId, UUID productId, int version, List<ProductAuthoring.FeeRule> rules) {
        scopeTo(tenantId);
        UUID versionId = draftVersionId(tenantId, productId, version);

        jdbc.update(
                "DELETE FROM product.fee_rules WHERE tenant_id = ? AND product_version_id = ?",
                tenantId,
                versionId);
        for (ProductAuthoring.FeeRule rule : rules) {
            jdbc.update(
                    "INSERT INTO product.fee_rules"
                            + " (tenant_id, product_version_id, operation, kind, flat_minor, basis_points,"
                            + "  cap_minor, currency, fee_account_id) VALUES (?,?,?,?,?,?,?,?,?)",
                    tenantId,
                    versionId,
                    rule.operation(),
                    rule.kind(),
                    rule.flatMinor(),
                    rule.basisPoints(),
                    rule.capMinor(),
                    rule.currency(),
                    rule.feeAccountId());
        }
    }

    @Override
    @Transactional(transactionManager = "productTransactionManager")
    public void setLimitRules(UUID tenantId, UUID productId, int version, List<ProductAuthoring.LimitRule> rules) {
        scopeTo(tenantId);
        UUID versionId = draftVersionId(tenantId, productId, version);

        jdbc.update(
                "DELETE FROM product.limit_rules WHERE tenant_id = ? AND product_version_id = ?",
                tenantId,
                versionId);
        for (ProductAuthoring.LimitRule rule : rules) {
            jdbc.update(
                    "INSERT INTO product.limit_rules"
                            + " (tenant_id, product_version_id, kyc_tier, channel, limit_type,"
                            + "  max_amount_minor, currency) VALUES (?,?,?,?,?,?,?)",
                    tenantId,
                    versionId,
                    rule.kycTier(),
                    rule.channel(),
                    rule.limitType(),
                    rule.maxAmountMinor(),
                    rule.currency());
        }
    }

    @Override
    @Transactional(transactionManager = "productTransactionManager")
    public void setLoanRule(UUID tenantId, UUID productId, int version, ProductAuthoring.LoanRule rule) {
        scopeTo(tenantId);
        UUID versionId = draftVersionId(tenantId, productId, version);

        jdbc.update(
                "DELETE FROM product.loan_rules WHERE tenant_id = ? AND product_version_id = ?",
                tenantId,
                versionId);
        if (rule == null) {
            return;
        }
        jdbc.update(
                "INSERT INTO product.loan_rules"
                        + " (tenant_id, product_version_id, interest_rate_bp, schedule_kind,"
                        + "  min_amount_minor, max_amount_minor, min_term_months, max_term_months,"
                        + "  grace_months, allocation_order, interest_income_account_id, prepayment_fee_bp,"
                        + "  currency, penalty_flat_minor, penalty_rate_bp, penalty_cap_minor,"
                        + "  penalty_income_account_id, funding_account_id)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                tenantId,
                versionId,
                rule.interestRateBp(),
                rule.scheduleKind(),
                rule.minAmountMinor(),
                rule.maxAmountMinor(),
                rule.minTermMonths(),
                rule.maxTermMonths(),
                rule.graceMonths(),
                rule.allocationOrder(),
                rule.interestIncomeAccountId(),
                rule.prepaymentFeeBp(),
                rule.currency(),
                rule.penaltyFlatMinor(),
                rule.penaltyRateBp(),
                rule.penaltyCapMinor(),
                rule.penaltyIncomeAccountId(),
                rule.fundingAccountId());
    }

    @Override
    @Transactional(transactionManager = "productTransactionManager")
    public void setEffectiveFrom(UUID tenantId, UUID productId, int version, String effectiveFrom) {
        scopeTo(tenantId);
        UUID versionId = draftVersionId(tenantId, productId, version);
        jdbc.update(
                "UPDATE product.product_versions SET effective_from = COALESCE(?::timestamptz, now())"
                        + " WHERE tenant_id = ? AND id = ?",
                effectiveFrom,
                tenantId,
                versionId);
    }

    @Override
    @Transactional(readOnly = true, transactionManager = "productTransactionManager")
    public ProductAuthoring.VersionDetail read(UUID tenantId, UUID productId, int version) {
        scopeTo(tenantId);

        Object[] header = jdbc.query(
                "SELECT v.id, v.status, v.effective_from, v.published_by, p.code, p.type"
                        + "   FROM product.product_versions v"
                        + "   JOIN product.products p ON p.tenant_id = v.tenant_id AND p.id = v.product_id"
                        + "  WHERE v.tenant_id = ? AND v.product_id = ? AND v.version = ?",
                rs ->
                        rs.next()
                                ? new Object[] {
                                    rs.getObject("id", UUID.class),
                                    rs.getString("status"),
                                    String.valueOf(rs.getObject("effective_from")),
                                    rs.getString("published_by"),
                                    rs.getString("code"),
                                    rs.getString("type")
                                }
                                : null,
                tenantId,
                productId,
                version);
        if (header == null) {
            return null;
        }
        UUID versionId = (UUID) header[0];

        List<ProductAuthoring.FeeRule> fees = jdbc.query(
                "SELECT operation, kind, flat_minor, basis_points, cap_minor, currency, fee_account_id"
                        + " FROM product.fee_rules WHERE tenant_id = ? AND product_version_id = ?"
                        + " ORDER BY operation",
                (rs, row) -> new ProductAuthoring.FeeRule(
                        rs.getString("operation"),
                        rs.getString("kind"),
                        (Long) rs.getObject("flat_minor"),
                        (Integer) rs.getObject("basis_points"),
                        (Long) rs.getObject("cap_minor"),
                        rs.getString("currency"),
                        rs.getObject("fee_account_id", UUID.class)),
                tenantId,
                versionId);

        List<ProductAuthoring.LimitRule> limits = jdbc.query(
                "SELECT kyc_tier, channel, limit_type, max_amount_minor, currency"
                        + " FROM product.limit_rules WHERE tenant_id = ? AND product_version_id = ?"
                        + " ORDER BY kyc_tier, channel, limit_type",
                (rs, row) -> new ProductAuthoring.LimitRule(
                        rs.getString("kyc_tier"),
                        rs.getString("channel"),
                        rs.getString("limit_type"),
                        rs.getLong("max_amount_minor"),
                        rs.getString("currency")),
                tenantId,
                versionId);

        List<ProductAuthoring.LoanRule> loan = jdbc.query(
                "SELECT interest_rate_bp, schedule_kind, min_amount_minor, max_amount_minor,"
                        + " min_term_months, max_term_months, grace_months, allocation_order,"
                        + " prepayment_fee_bp, penalty_flat_minor, penalty_rate_bp, penalty_cap_minor,"
                        + " currency, interest_income_account_id, penalty_income_account_id, funding_account_id"
                        + " FROM product.loan_rules WHERE tenant_id = ? AND product_version_id = ?",
                (rs, row) -> new ProductAuthoring.LoanRule(
                        rs.getInt("interest_rate_bp"),
                        rs.getString("schedule_kind"),
                        rs.getLong("min_amount_minor"),
                        rs.getLong("max_amount_minor"),
                        rs.getInt("min_term_months"),
                        rs.getInt("max_term_months"),
                        rs.getInt("grace_months"),
                        rs.getString("allocation_order"),
                        rs.getInt("prepayment_fee_bp"),
                        rs.getLong("penalty_flat_minor"),
                        rs.getInt("penalty_rate_bp"),
                        (Long) rs.getObject("penalty_cap_minor"),
                        rs.getString("currency"),
                        rs.getObject("interest_income_account_id", UUID.class),
                        rs.getObject("penalty_income_account_id", UUID.class),
                        rs.getObject("funding_account_id", UUID.class)),
                tenantId,
                versionId);

        return new ProductAuthoring.VersionDetail(
                productId,
                (String) header[4],
                (String) header[5],
                version,
                (String) header[1],
                (String) header[2],
                (String) header[3],
                fees,
                limits,
                loan.isEmpty() ? null : loan.get(0));
    }

    /** Kept out of the API surface: {@code List.copyOf} on an empty list, spelled once. */
    static <T> List<T> orEmpty(List<T> values) {
        return values == null ? new ArrayList<>() : values;
    }
}
