package org.elyonar.fincore.core.product.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.elyonar.fincore.core.product.api.ProductDecision;
import org.elyonar.fincore.core.product.api.ProductDecisions;
import org.elyonar.fincore.core.product.api.ProductRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.elyonar.fincore.core.product.api.ProductBeans;

/**
 * Product's decision for an intended operation, under the configuration live right now.
 *
 * <p>Returns decisions, never postings. The version that produced the decision travels with it so
 * Orchestration can record it on the saga: a completed transaction has to stay explicable after the
 * configuration moves on, and "why was this fee ₦20 last March" needs an answer that is not
 * today's rules.
 */
@Service
public class JdbcProductDecisions implements ProductDecisions {

    /** Returned when no configuration was found, so a refusal still carries a legible version. */
    private static final int NO_VERSION = 0;

    private final JdbcTemplate jdbc;

    public JdbcProductDecisions(@Qualifier(ProductBeans.JDBC) JdbcTemplate productJdbcTemplate) {
        this.jdbc = productJdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true, transactionManager = ProductBeans.TRANSACTION_MANAGER)
    public ProductDecision evaluate(ProductRequest request) {
        jdbc.queryForObject("SELECT set_config(\'app.tenant_id\', ?, true)", String.class, request.tenantId().toString());

        // The live version: highest `version` whose `effective_from` has arrived, among published
        // ones. Both columns are load-bearing — see the migration.
        Map<String, Object> version =
                jdbc.query(
                        """
                        SELECT v.id, v.version
                          FROM product.product_versions v
                          JOIN product.products p ON p.id = v.product_id
                         WHERE p.code = ? AND v.status = 'PUBLISHED' AND v.effective_from <= now()
                         ORDER BY v.version DESC
                         LIMIT 1
                        """,
                        rs -> rs.next() ? Map.of("id", rs.getObject("id"), "version", rs.getInt("version")) : null,
                        request.productCode());

        if (version == null) {
            return ProductDecision.refused(ProductDecision.Refusal.PRODUCT_NOT_FOUND, NO_VERSION);
        }
        Object versionId = version.get("id");
        int versionNumber = (Integer) version.get("version");

        Long limitMinor = limitFor(versionId, request, "PER_TXN");
        if (limitMinor == null) {
            // No limit rule for this tier and channel means the product does not offer this
            // operation to this customer — a refusal, not an unlimited allowance. Deny by default
            // applies to money as much as to permissions.
            return ProductDecision.refused(ProductDecision.Refusal.OPERATION_NOT_PERMITTED, versionNumber);
        }
        if (request.amountMinor() > limitMinor) {
            return ProductDecision.refused(ProductDecision.Refusal.LIMIT_EXCEEDED, versionNumber);
        }

        Fee fee = fee(versionId, request);
        if (fee == null) {
            // Fee rules exist for this operation, none in this currency. The operation is priced —
            // just not for the money being moved — and "no rule in this currency ⇒ free" priced
            // every second-currency transaction at zero against a version that charges for it.
            return ProductDecision.refused(ProductDecision.Refusal.CURRENCY_MISMATCH, versionNumber);
        }
        // The DAILY rule is stated here and enforced by Orchestration, which holds the day's
        // running total of reservations. Product cannot enforce it alone: two concurrent
        // transfers each pass a here-and-now check, which is the race reservations exist for.
        Long dailyLimitMinor = limitFor(versionId, request, "DAILY");
        return ProductDecision.permitted(
                fee.amountMinor(), fee.accountId(), limitMinor, dailyLimitMinor, versionNumber);
    }

    /**
     * The bound for this tier, channel and type — in this currency.
     *
     * <p>Currency was in the WHERE clause of neither this query nor the fee one, though every rule
     * table carries it {@code NOT NULL}. A ₦500,000 per-transaction ceiling therefore applied,
     * unconverted, to a transaction in dollars. It has never fired because one currency is seeded,
     * and a limit that is right only while nobody adds a second currency is not a limit.
     */
    private Long limitFor(Object versionId, ProductRequest request, String limitType) {
        return jdbc.query(
                """
                SELECT max_amount_minor FROM product.limit_rules
                 WHERE product_version_id = ? AND kyc_tier = ? AND channel = ? AND limit_type = ?
                   AND currency = ?
                """,
                rs -> rs.next() ? rs.getLong(1) : null,
                versionId,
                request.kycTier(),
                request.channel(),
                limitType,
                request.currency());
    }

    /**
     * The fee for this operation, or null when the operation is priced but not in this currency.
     *
     * <p>No rule for the operation <em>at all</em> means no fee — an absent price is free, which is
     * the only reading that cannot silently overcharge. But that reading used to be applied per
     * currency: the query filtered on currency and treated an empty result as free, so a version
     * with an NGN fee rule priced a USD withdrawal at zero. Free-by-omission and
     * unpriced-in-this-currency are different facts, so both are read in one query and told apart
     * here.
     */
    private Fee fee(Object versionId, ProductRequest request) {
        record Row(String currency, String kind, long flat, int bps, long cap, UUID accountId) {}
        List<Row> rules =
                jdbc.query(
                        """
                        SELECT currency, kind, flat_minor, basis_points, cap_minor, fee_account_id
                          FROM product.fee_rules
                         WHERE product_version_id = ? AND operation = ?
                        """,
                        (rs, i) ->
                                new Row(
                                        rs.getString("currency"),
                                        rs.getString("kind"),
                                        rs.getObject("flat_minor") == null ? 0L : rs.getLong("flat_minor"),
                                        rs.getObject("basis_points") == null ? 0 : rs.getInt("basis_points"),
                                        rs.getObject("cap_minor") == null ? -1L : rs.getLong("cap_minor"),
                                        rs.getObject("fee_account_id", UUID.class)),
                        versionId,
                        request.operation().name());

        if (rules.isEmpty()) {
            // Genuinely free: the version prices nothing for this operation, in any currency.
            return new Fee(0L, null);
        }
        Row rule =
                rules.stream()
                        .filter(r -> request.currency().equals(r.currency()))
                        .findFirst()
                        .orElse(null);
        if (rule == null) {
            // Priced, but not in this currency. The caller refuses rather than waiving the charge.
            return null;
        }

        long computed;
        if ("FLAT".equals(rule.kind())) {
            computed = rule.flat();
        } else {
            // Integer arithmetic throughout. Basis points are hundredths of a percent, so the
            // divisor is 10_000; the division truncates, which rounds the fee *down* — in the
            // customer's favour, and deterministically rather than by a floating-point rule
            // nobody can reproduce.
            computed = (request.amountMinor() * rule.bps()) / 10_000L;
        }

        long cap = rule.cap();
        return new Fee(cap >= 0 ? Math.min(computed, cap) : computed, rule.accountId());
    }

    /** The fee and, when configured, the account it credits — pricing facts travel together. */
    private record Fee(long amountMinor, UUID accountId) {}
}
