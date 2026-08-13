package org.elyonar.fincore.customer.internal;

import java.util.UUID;
import javax.sql.DataSource;
import org.elyonar.fincore.customer.api.CustomerBeans;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Answers whether a tenant exists at all.
 *
 * <p>Inside Core, {@code platform.tenants} answered this for every module at once and Core's gate
 * enforced it. This service has its own database and cannot see that table, so it keeps its own —
 * the same arrangement Ledger, Identity and Notification each landed on independently.
 *
 * <p>Row-level security isolates tenants from one another and has nothing to say about whether a
 * tenant is real. Without this, any UUID in a validated token was a working institution with an
 * empty catalogue, which reads to an operator as "nothing configured yet" rather than as "this
 * institution does not exist here".
 *
 * <p>Two identities, because these are two jobs: the check runs as the app role, which holds
 * SELECT and nothing more; registration is provisioning and runs as the owner.
 */
@Component
public class TenantRegistry {

    private final JdbcTemplate read;
    private final JdbcTemplate provision;

    public TenantRegistry(
            @Qualifier(CustomerBeans.JDBC) JdbcTemplate read, @Qualifier("dataSource") DataSource owner) {
        this.read = read;
        this.provision = new JdbcTemplate(owner);
    }

    public boolean isActive(UUID tenantId) {
        String status = read.query(
                "SELECT status FROM customer.tenants WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                tenantId);
        return "ACTIVE".equals(status);
    }

    /** Provisioning only, and deliberately not reachable from any request path. */
    public void register(UUID tenantId, String name, String createdBy) {
        provision.update(
                "INSERT INTO customer.tenants (id, name, created_by) VALUES (?,?,?)"
                        + " ON CONFLICT (id) DO NOTHING",
                tenantId, name, createdBy);
    }
}
