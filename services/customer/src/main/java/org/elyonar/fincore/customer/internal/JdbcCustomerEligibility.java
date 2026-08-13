package org.elyonar.fincore.customer.internal;

import java.util.UUID;
import org.elyonar.fincore.customer.api.CustomerEligibility;
import org.elyonar.fincore.customer.api.EligibilityResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.elyonar.fincore.customer.api.CustomerBeans;

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

    public JdbcCustomerEligibility(@Qualifier(CustomerBeans.JDBC) JdbcTemplate customerJdbcTemplate) {
        this.jdbc = customerJdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true, transactionManager = CustomerBeans.TRANSACTION_MANAGER)
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
    @Transactional(readOnly = true, transactionManager = CustomerBeans.TRANSACTION_MANAGER)
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

    /**
     * The product, read under the same predicate {@code holdsAccount} uses.
     *
     * <p>One query rather than "does she hold it" followed by "what is it": two reads could
     * disagree across an unlink, and the money path would then price a transaction against an
     * account the customer no longer holds. The null return folds both refusals together, which is
     * what the caller wants — it refuses either way.
     */
    @Override
    @Transactional(readOnly = true, transactionManager = CustomerBeans.TRANSACTION_MANAGER)
    public String productOfHeldAccount(UUID tenantId, UUID customerId, UUID ledgerAccountId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());

        return jdbc.query(
                """
                SELECT product_code FROM customer.customer_accounts
                 WHERE customer_id = ? AND ledger_account_id = ? AND unlinked_at IS NULL
                """,
                rs -> rs.next() ? rs.getString("product_code") : null,
                customerId,
                ledgerAccountId);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(
            readOnly = true,
            transactionManager = "customerTransactionManager")
    public java.util.List<HeldAccount> heldAccounts(java.util.UUID tenantId, java.util.UUID customerId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
        return jdbc.query(
                """
                SELECT ledger_account_id, account_number, currency, role, product_code
                  FROM customer.customer_accounts
                 WHERE customer_id = ? AND unlinked_at IS NULL
                 ORDER BY linked_at
                """,
                (rs, i) ->
                        new HeldAccount(
                                rs.getObject("ledger_account_id", java.util.UUID.class),
                                rs.getString("account_number"),
                                rs.getString("currency"),
                                rs.getString("role"),
                                rs.getString("product_code")),
                customerId);
    }
}