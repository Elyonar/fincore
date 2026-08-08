package org.elyonar.fincore.core.product.internal;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.core.product.api.LoanProducts;
import org.elyonar.fincore.core.product.api.ProductBeans;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Product's answer to Lending's one question, in the shape every port here takes: read-only,
 * tenant-scoped on its own connection, live-version selection identical to fee evaluation —
 * highest published version whose {@code effective_from} has arrived.
 */
@Service
public class JdbcLoanProducts implements LoanProducts {

    private final JdbcTemplate jdbc;

    public JdbcLoanProducts(@Qualifier(ProductBeans.JDBC) JdbcTemplate productJdbcTemplate) {
        this.jdbc = productJdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true, transactionManager = ProductBeans.TRANSACTION_MANAGER)
    public LoanTerms termsFor(UUID tenantId, String productCode) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
        return jdbc.query(
                """
                SELECT v.version, r.interest_rate_bp, r.schedule_kind, r.min_amount_minor,
                       r.max_amount_minor, r.min_term_months, r.max_term_months, r.grace_months,
                       r.allocation_order, r.interest_income_account_id, r.prepayment_fee_bp, r.currency,
                       r.penalty_flat_minor, r.penalty_rate_bp, r.penalty_cap_minor,
                       r.penalty_income_account_id, r.funding_account_id
                  FROM product.product_versions v
                  JOIN product.products p ON p.id = v.product_id
                  JOIN product.loan_rules r ON r.product_version_id = v.id
                 WHERE p.code = ? AND p.type = 'LOAN'
                   AND v.status = 'PUBLISHED' AND v.effective_from <= now()
                 ORDER BY v.version DESC
                 LIMIT 1
                """,
                rs ->
                        rs.next()
                                ? new LoanTerms(
                                        rs.getInt("version"),
                                        rs.getInt("interest_rate_bp"),
                                        rs.getString("schedule_kind"),
                                        rs.getLong("min_amount_minor"),
                                        rs.getLong("max_amount_minor"),
                                        rs.getInt("min_term_months"),
                                        rs.getInt("max_term_months"),
                                        rs.getInt("grace_months"),
                                        order(rs.getString("allocation_order")),
                                        rs.getObject("interest_income_account_id", UUID.class),
                                        rs.getInt("prepayment_fee_bp"),
                                        rs.getString("currency"),
                                        rs.getLong("penalty_flat_minor"),
                                        rs.getInt("penalty_rate_bp"),
                                        rs.getObject("penalty_cap_minor") == null
                                                ? null
                                                : rs.getLong("penalty_cap_minor"),
                                        rs.getObject("penalty_income_account_id", UUID.class),
                                        rs.getObject("funding_account_id", UUID.class))
                                : null,
                productCode);
    }

    private static List<String> order(String csv) {
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
