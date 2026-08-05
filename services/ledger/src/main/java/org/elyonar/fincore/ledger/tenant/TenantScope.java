package org.elyonar.fincore.ledger.tenant;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work in a transaction whose database session is scoped to one tenant.
 *
 * <p>The context is applied with {@code set_config(..., is_local => true)} — the parameterised
 * form of {@code SET LOCAL} — so PostgreSQL discards it when the transaction ends. That detail is
 * load-bearing rather than stylistic: connections are pooled and reused across tenants, and a
 * session-scoped {@code SET} would return to the pool still carrying the previous tenant's
 * identity for the next borrower to inherit. Row-level security exists to catch a query that
 * forgot its tenant, so a leaked context would disable it in precisely the case it is there for.
 *
 * <p>Everything the ledger writes runs through here, which is what makes RLS a real backstop
 * rather than a decoration.
 */
@Component
public class TenantScope {

    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;

    public TenantScope(TransactionTemplate transactions, JdbcTemplate jdbc) {
        this.transactions = transactions;
        this.jdbc = jdbc;
    }

    public <T> T inTenant(UUID tenantId, Supplier<T> work) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        return transactions.execute(status -> {
            jdbc.queryForObject(
                    "SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            TenantContext.set(tenantId);
            try {
                return work.get();
            } finally {
                TenantContext.clear();
            }
        });
    }

    public void inTenant(UUID tenantId, Runnable work) {
        inTenant(
                tenantId,
                () -> {
                    work.run();
                    return null;
                });
    }
}
