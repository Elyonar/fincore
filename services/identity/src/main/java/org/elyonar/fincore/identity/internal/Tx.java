package org.elyonar.fincore.identity.internal;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tenant-scoped units of work over the restricted app role.
 *
 * <p>{@code SET LOCAL} inside the transaction, never a session SET (ADR 0007): connections are
 * pooled, and a session variable would hand the next borrower the previous tenant's identity —
 * the failure row-level security exists to catch, reproduced by the tool meant to prevent it.
 */
@Component
public class Tx {

    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;

    public Tx(TransactionTemplate tx, @Qualifier("appJdbcTemplate") JdbcTemplate jdbc) {
        this.tx = tx;
        this.jdbc = jdbc;
    }

    /** Runs {@code work} in one transaction scoped to {@code tenantId}. */
    public <T> T inTenant(UUID tenantId, Supplier<T> work) {
        return tx.execute(status -> {
            jdbc.queryForObject(
                    "SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            return work.get();
        });
    }

    /** Runs {@code work} in one transaction with no tenant context (registry, service clients). */
    public <T> T plain(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }
}
