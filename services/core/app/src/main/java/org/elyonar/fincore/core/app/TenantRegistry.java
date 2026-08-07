package org.elyonar.fincore.core.app;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Answers whether a tenant exists at all.
 *
 * <p>Row-level security isolates tenants from one another; it has nothing to say about whether a
 * tenant is real. Before this existed, any UUID in a validated token was a working tenant — it got
 * its own empty, functioning slice of Core, and every isolation test passed, because isolation was
 * never the thing that was broken. The ledger reached the same conclusion in its V6 and the
 * reasoning is quoted there; this is the same medicine at the second deployable that needed it.
 *
 * <p>Lives in {@code app} rather than in a module because a tenant is a fact about the deployable.
 * The read is deliberately not tenant-scoped: a request must be able to ask "is this tenant real?"
 * before it has a tenant context to be scoped by.
 */
@Component
public class TenantRegistry {

    private final JdbcTemplate read;
    private final JdbcTemplate provision;

    /**
     * Two identities, because these are two different jobs.
     *
     * <p>The check runs on every request as a module role, which holds SELECT and nothing more.
     * Registration is provisioning and runs as the owner — the same separation the scaffold demands
     * of DDL and traffic, and the reason a request path cannot enrol its own tenant even if
     * somebody later wires an endpoint to this class by mistake.
     */
    public TenantRegistry(
            @Qualifier("orchestrationJdbcTemplate") JdbcTemplate read,
            @Qualifier("ownerDataSource") javax.sql.DataSource owner) {
        this.read = read;
        this.provision = new JdbcTemplate(owner);
    }

    /** Whether this tenant is registered and active. */
    public boolean isActive(UUID tenantId) {
        String status = read.query(
                "SELECT status FROM platform.tenants WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                tenantId);
        return "ACTIVE".equals(status);
    }

    /**
     * Registers a tenant.
     *
     * <p>Provisioning only, and deliberately not reachable from any request path — there is no
     * endpoint that calls this. A service that could enrol its own caller's tenant would be back to
     * trusting the token's claim, which is the thing this class exists to stop.
     */
    public void register(UUID tenantId, String name, String createdBy) {
        provision.update(
                "INSERT INTO platform.tenants (id, name, created_by) VALUES (?,?,?)"
                        + " ON CONFLICT (id) DO NOTHING",
                tenantId, name, createdBy);
    }
}
