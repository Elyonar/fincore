package org.elyonar.fincore.core.product.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.elyonar.fincore.core.product.api.ProductAuthoring;
import org.elyonar.fincore.core.product.api.ProductErrorReason;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writing pricing.
 *
 * <p>The rule this class exists to hold is the one {@link ProductRecords} already holds for
 * versions: <strong>a published version is never edited</strong>. Every method here resolves the
 * version first and refuses if it is live. The database refuses too — V7 put the same guard on
 * both rule tables — and the check here exists to produce a decent error rather than a constraint
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

    /**
     * The version row's id, refusing when it is absent or live — and <b>locked</b>.
     *
     * <p>{@code FOR UPDATE} is what makes the status check mean anything under concurrency. Without
     * it, a rule write could read DRAFT while a publish of the same version was in flight: the
     * publish's non-key UPDATE does not conflict with the rule INSERTs' foreign-key locks, both
     * would commit, and the result would be a PUBLISHED version whose rules differ from what the
     * checker signed. Holding the row lock serialises every rule write and reschedule against
     * publish, which takes the same lock.
     */
    private UUID draftVersionId(UUID tenantId, UUID productId, int version) {
        String[] found = jdbc.query(
                "SELECT id::text, status FROM product.product_versions"
                        + " WHERE tenant_id = ? AND product_id = ? AND version = ?"
                        + " FOR UPDATE",
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
    public int draftNextVersion(UUID tenantId, UUID productId, Integer copyFrom, String author) {
        scopeTo(tenantId);
        if (author == null || author.isBlank()) {
            // Attribution is what maker-checker compares at publish. Recording a blank here would
            // let anyone publish this draft — or no one — depending on how the comparison fell.
            throw new IllegalArgumentException("a draft must record who drafted it");
        }

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

        UUID newVersionId;
        try {
            newVersionId = jdbc.queryForObject(
                    "INSERT INTO product.product_versions (tenant_id, product_id, version, status, created_by)"
                            + " VALUES (?,?,?,'DRAFT',?) RETURNING id",
                    UUID.class,
                    tenantId,
                    productId,
                    next,
                    author);
        } catch (DuplicateKeyException raced) {
            // Two administrators drafted "the next version" at once and the unique index picked a
            // winner. Retrying inside this transaction is not possible — the violation poisoned it —
            // so the loser is told, as a 409, and their retry drafts the version after the winner's.
            throw new DraftConflict();
        }

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
    }

    // ---------------------------------------------------------------- shape validation
    //
    // Rescued from the withdrawn authoring stack's RuleValidation, which died with lending while
    // the surviving controller called none of it — leaving a 500% fee storable and publishable.
    // The checks live here, not in the controller, because this is the module that owns the tables:
    // a second authoring surface would inherit them by construction instead of by remembering.
    // Account checks stay in the controller, which is the only place that can see Orchestration.

    private static final Set<String> OPERATIONS = Set.of("DEPOSIT", "WITHDRAWAL", "TRANSFER");
    private static final Set<String> FEE_KINDS = Set.of("FLAT", "PERCENT");
    private static final Set<String> LIMIT_TYPES = Set.of("PER_TXN", "DAILY");

    /**
     * Mirrored vocabularies, and deliberately so.
     *
     * <p>The channel set lives in Orchestration's transfer surface (ADR 0012), which Product may
     * not import; the KYC tiers are the CBN three that {@code customer.customers.kyc_tier}'s own
     * default comes from. A divergence here refuses a value the money path would accept — the safe
     * direction to be wrong in, because a limit rule for a tier or channel that never matches is a
     * limit that silently never applies, and the evaluator denies by default.
     */
    private static final Set<String> CHANNELS = Set.of("TELLER", "API");

    private static final Set<String> KYC_TIERS = Set.of("TIER_1", "TIER_2", "TIER_3");

    private static void checkFeeShape(ProductAuthoring.FeeRule rule) {
        if (!OPERATIONS.contains(rule.operation())) {
            throw new RulesInvalid(
                    ProductErrorReason.UNKNOWN_OPERATION,
                    Map.of("operation", String.valueOf(rule.operation()), "permitted", OPERATIONS.toString()));
        }
        if (!FEE_KINDS.contains(rule.kind())) {
            throw new RulesInvalid(
                    ProductErrorReason.UNKNOWN_FEE_BASIS,
                    Map.of("kind", String.valueOf(rule.kind()), "permitted", FEE_KINDS.toString()));
        }
        // The database says the same in `fee_shape`. This says it in a sentence.
        if ("FLAT".equals(rule.kind()) && (rule.flatMinor() == null || rule.basisPoints() != null)) {
            throw new RulesInvalid(
                    ProductErrorReason.UNKNOWN_FEE_BASIS, Map.of("kind", "FLAT", "expects", "flatMinor, and no basisPoints"));
        }
        if ("PERCENT".equals(rule.kind()) && (rule.basisPoints() == null || rule.flatMinor() != null)) {
            throw new RulesInvalid(
                    ProductErrorReason.UNKNOWN_FEE_BASIS,
                    Map.of("kind", "PERCENT", "expects", "basisPoints, and no flatMinor"));
        }
        if (rule.basisPoints() != null && (rule.basisPoints() < 0 || rule.basisPoints() > 10_000)) {
            // 10,000 basis points is 100%. A fee above the amount is a typed zero too many.
            throw new RulesInvalid(
                    ProductErrorReason.RATE_OUT_OF_RANGE, Map.of("basisPoints", rule.basisPoints(), "max", 10_000));
        }
        if ((rule.flatMinor() != null && rule.flatMinor() < 0) || (rule.capMinor() != null && rule.capMinor() < 0)) {
            throw new RulesInvalid(
                    ProductErrorReason.BOUNDS_INVERTED, Map.of("field", "flatMinor/capMinor", "reason", "negative"));
        }
        checkCurrency(rule.currency());
    }

    private static void checkLimitShape(ProductAuthoring.LimitRule rule) {
        if (!KYC_TIERS.contains(rule.kycTier())) {
            throw new RulesInvalid(
                    ProductErrorReason.UNKNOWN_KYC_TIER,
                    Map.of("kycTier", String.valueOf(rule.kycTier()), "permitted", KYC_TIERS.toString()));
        }
        if (!CHANNELS.contains(rule.channel())) {
            throw new RulesInvalid(
                    ProductErrorReason.UNKNOWN_CHANNEL,
                    Map.of("channel", String.valueOf(rule.channel()), "permitted", CHANNELS.toString()));
        }
        if (!LIMIT_TYPES.contains(rule.limitType())) {
            throw new RulesInvalid(
                    ProductErrorReason.UNKNOWN_LIMIT_TYPE,
                    Map.of("limitType", String.valueOf(rule.limitType()), "permitted", LIMIT_TYPES.toString()));
        }
        if (rule.maxAmountMinor() <= 0) {
            throw new RulesInvalid(
                    ProductErrorReason.BOUNDS_INVERTED, Map.of("field", "maxAmountMinor", "reason", "must be above zero"));
        }
        checkCurrency(rule.currency());
    }

    private static void checkCurrency(String currency) {
        if (currency == null || currency.length() != 3 || !currency.equals(currency.toUpperCase(java.util.Locale.ROOT))) {
            throw new RulesInvalid(
                    ProductErrorReason.CURRENCY_INVALID,
                    Map.of("currency", String.valueOf(currency), "expects", "an uppercase ISO 4217 code"));
        }
    }

    @Override
    @Transactional(transactionManager = "productTransactionManager")
    public void setFeeRules(UUID tenantId, UUID productId, int version, List<ProductAuthoring.FeeRule> rules) {
        for (ProductAuthoring.FeeRule rule : rules) {
            checkFeeShape(rule);
        }
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
        for (ProductAuthoring.LimitRule rule : rules) {
            checkLimitShape(rule);
        }
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

    /**
     * The moment, as an instant, or null.
     *
     * <p>This read used to hand back {@code String.valueOf(rs.getObject(...))}, which is a
     * {@code Timestamp.toString()} — the server's local wall clock with no zone on it, and the
     * four-character string {@code "null"} when the column was empty. Both are unreconcilable by a
     * caller: a pricing change that starts "at 22:54, somewhere" is a pricing change nobody can
     * reason about, and a literal "null" is a date that parses as a date until it doesn't. The
     * catalogue read has always returned an {@code OffsetDateTime}; this now agrees with it.
     */
    private static String instant(java.time.OffsetDateTime moment) {
        return moment == null ? null : moment.toInstant().toString();
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
                                    instant(rs.getObject("effective_from", java.time.OffsetDateTime.class)),
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

        return new ProductAuthoring.VersionDetail(
                productId,
                (String) header[4],
                (String) header[5],
                version,
                (String) header[1],
                (String) header[2],
                (String) header[3],
                fees,
                limits);
    }

    /** Kept out of the API surface: {@code List.copyOf} on an empty list, spelled once. */
    static <T> List<T> orEmpty(List<T> values) {
        return values == null ? new ArrayList<>() : values;
    }
}
