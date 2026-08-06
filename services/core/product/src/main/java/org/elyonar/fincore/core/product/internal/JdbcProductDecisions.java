package org.elyonar.fincore.core.product.internal;

import java.util.Map;
import org.elyonar.fincore.core.product.api.ProductDecision;
import org.elyonar.fincore.core.product.api.ProductDecisions;
import org.elyonar.fincore.core.product.api.ProductRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public JdbcProductDecisions(@Qualifier("productJdbcTemplate") JdbcTemplate productJdbcTemplate) {
        this.jdbc = productJdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true, transactionManager = "productTransactionManager")
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

        Long limitMinor = perTransactionLimit(versionId, request);
        if (limitMinor == null) {
            // No limit rule for this tier and channel means the product does not offer this
            // operation to this customer — a refusal, not an unlimited allowance. Deny by default
            // applies to money as much as to permissions.
            return ProductDecision.refused(ProductDecision.Refusal.OPERATION_NOT_PERMITTED, versionNumber);
        }
        if (request.amountMinor() > limitMinor) {
            return ProductDecision.refused(ProductDecision.Refusal.LIMIT_EXCEEDED, versionNumber);
        }

        return ProductDecision.permitted(fee(versionId, request), limitMinor, versionNumber);
    }

    private Long perTransactionLimit(Object versionId, ProductRequest request) {
        return jdbc.query(
                """
                SELECT max_amount_minor FROM product.limit_rules
                 WHERE product_version_id = ? AND kyc_tier = ? AND channel = ? AND limit_type = 'PER_TXN'
                """,
                rs -> rs.next() ? rs.getLong(1) : null,
                versionId,
                request.kycTier(),
                request.channel());
    }

    /**
     * The fee for this operation. No rule means no fee — an absent price is free, which is the only
     * reading that cannot silently overcharge.
     */
    private long fee(Object versionId, ProductRequest request) {
        Map<String, Object> rule =
                jdbc.query(
                        """
                        SELECT kind, flat_minor, basis_points, cap_minor
                          FROM product.fee_rules
                         WHERE product_version_id = ? AND operation = ?
                        """,
                        rs -> {
                            if (!rs.next()) {
                                return null;
                            }
                            return Map.of(
                                    "kind", rs.getString("kind"),
                                    "flat", rs.getObject("flat_minor") == null ? 0L : rs.getLong("flat_minor"),
                                    "bps", rs.getObject("basis_points") == null ? 0 : rs.getInt("basis_points"),
                                    "cap", rs.getObject("cap_minor") == null ? -1L : rs.getLong("cap_minor"));
                        },
                        versionId,
                        request.operation().name());

        if (rule == null) {
            return 0L;
        }

        long computed;
        if ("FLAT".equals(rule.get("kind"))) {
            computed = (Long) rule.get("flat");
        } else {
            // Integer arithmetic throughout. Basis points are hundredths of a percent, so the
            // divisor is 10_000; the division truncates, which rounds the fee *down* — in the
            // customer's favour, and deterministically rather than by a floating-point rule
            // nobody can reproduce.
            computed = (request.amountMinor() * (Integer) rule.get("bps")) / 10_000L;
        }

        long cap = (Long) rule.get("cap");
        return cap >= 0 ? Math.min(computed, cap) : computed;
    }
}
