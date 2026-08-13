package org.elyonar.fincore.notification.internal;

import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Answers whether a tenant exists at all.
 *
 * <p>This service has two doors and both need it. A request arrives with a validated token, and an
 * event arrives with a tenant in its envelope — and neither is evidence that the tenant was ever
 * provisioned here. Before this, an event naming an unknown tenant would create templates' worth of
 * suppressions under it, and a token naming one would return an empty policy as though the
 * configuration simply had not been done yet.
 *
 * <p>Two identities, because these are two jobs: the check runs as the app role, which holds SELECT
 * and nothing more; registration is provisioning and runs as the owner.
 */
@Component
public class TenantRegistry {

    private final JdbcTemplate read;
    private final JdbcTemplate provision;

    public TenantRegistry(
            @Qualifier("appJdbcTemplate") JdbcTemplate read, @Qualifier("dataSource") DataSource owner) {
        this.read = read;
        this.provision = new JdbcTemplate(owner);
    }

    public boolean isActive(UUID tenantId) {
        String status = read.query(
                "SELECT status FROM notification.tenants WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                tenantId);
        return "ACTIVE".equals(status);
    }

    /**
     * The institution's name, as a message signs itself.
     *
     * <p>An SMS about somebody's money that does not say who it is from is indistinguishable from
     * the ones criminals send, and a customer who cannot tell is a customer who either ignores a
     * real alert or answers a fake one. Null only if the tenant vanished between the gate and here.
     */
    public String displayName(UUID tenantId) {
        return read.query(
                "SELECT name FROM notification.tenants WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                tenantId);
    }

    /** Provisioning only, and deliberately not reachable from any request or event path. */
    public void register(UUID tenantId, String name, String createdBy) {
        provision.update(
                "INSERT INTO notification.tenants (id, name, created_by) VALUES (?,?,?)"
                        + " ON CONFLICT (id) DO NOTHING",
                tenantId, name, createdBy);
    }
}
