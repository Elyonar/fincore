package org.elyonar.fincore.core.customer.internal;

import java.util.UUID;
import org.elyonar.fincore.core.customer.api.CustomerEligibility;
import org.elyonar.fincore.core.customer.api.EligibilityResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer's answers to the two questions Orchestration is allowed to ask.
 *
 * <p>Read-only, deliberately. Customer is consulted inside Orchestration's Phase A transaction and
 * writes nothing there — which is what keeps Phase A's atomicity a property of one module's
 * connection rather than a distributed problem across three.
 *
 * <p>Each query sets the tenant context on its own connection, because row-level security is the
 * backstop for the case where a query forgot to scope itself, and a backstop that is only active
 * sometimes is not one.
 */
@Service
public class JdbcCustomerEligibility implements CustomerEligibility {

    private final JdbcTemplate jdbc;

    public JdbcCustomerEligibility(@Qualifier("customerJdbcTemplate") JdbcTemplate customerJdbcTemplate) {
        this.jdbc = customerJdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true, transactionManager = "customerTransactionManager")
    public EligibilityResult check(UUID tenantId, UUID customerId) {
        // SET LOCAL, never a session SET: connections are pooled across tenants, and a session
        // variable would travel back into the pool carrying the last tenant's identity.
        jdbc.queryForObject("SELECT set_config(\'app.tenant_id\', ?, true)", String.class, tenantId.toString());

        String status =
                jdbc.query(
                        "SELECT status, kyc_tier FROM customer.customers WHERE id = ?",
                        rs -> rs.next() ? rs.getString("status") + "|" + rs.getString("kyc_tier") : null,
                        customerId);

        if (status == null) {
            // Not-found and wrong-tenant are deliberately indistinguishable: row-level security
            // has already hidden another tenant's customer, and telling a caller which case it hit
            // would confirm that a customer exists somewhere.
            return EligibilityResult.refused(EligibilityResult.Reason.NOT_FOUND);
        }

        String[] parts = status.split("\\|", 2);
        if (!"ACTIVE".equals(parts[0])) {
            return EligibilityResult.refused(EligibilityResult.Reason.NOT_ACTIVE);
        }
        return EligibilityResult.eligible(parts[1]);
    }

    @Override
    @Transactional(readOnly = true, transactionManager = "customerTransactionManager")
    public boolean holdsAccount(UUID tenantId, UUID customerId, UUID ledgerAccountId) {
        jdbc.queryForObject("SELECT set_config(\'app.tenant_id\', ?, true)", String.class, tenantId.toString());

        Integer found =
                jdbc.query(
                        """
                        SELECT 1 FROM customer.customer_accounts
                         WHERE customer_id = ? AND ledger_account_id = ? AND unlinked_at IS NULL
                        """,
                        rs -> rs.next() ? 1 : null,
                        customerId,
                        ledgerAccountId);
        return found != null;
    }
}
