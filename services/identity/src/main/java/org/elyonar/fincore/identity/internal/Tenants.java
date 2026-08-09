package org.elyonar.fincore.identity.internal;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The tenant registry, and the answer to "which institution does this instance authenticate?"
 *
 * <p>One deployed instance serves one institution (ADR 0018). With one seeded tenant the answer
 * is that tenant; with several, deployment must name it, and startup refuses the ambiguity —
 * login guessing among institutions would be a wrong-bank bug wearing a convenience.
 *
 * <p>Registration is provisioning and runs as the owner, deliberately unreachable from any
 * request path — the same split as every other registry on the platform.
 */
@Component
public class Tenants {

    private final JdbcTemplate read;
    private final JdbcTemplate provision;
    private final IdentityProperties properties;

    public Tenants(
            @Qualifier("appJdbcTemplate") JdbcTemplate read,
            @Qualifier("dataSource") DataSource owner,
            IdentityProperties properties) {
        this.read = read;
        this.provision = new JdbcTemplate(owner);
        this.properties = properties;
    }

    /** Provisioning only (manifest seeding). */
    public void register(UUID tenantId, String name, String createdBy) {
        provision.update(
                "INSERT INTO identity.tenants (id, name, created_by) VALUES (?,?,?)"
                        + " ON CONFLICT (id) DO NOTHING",
                tenantId,
                name,
                createdBy);
    }

    public List<UUID> activeTenants() {
        return read.query(
                "SELECT id FROM identity.tenants WHERE status = 'ACTIVE' ORDER BY id",
                (rs, i) -> rs.getObject(1, UUID.class));
    }

    /**
     * The instance's tenant, or null when none exists yet. Throws on ambiguity rather than
     * guessing — the configured id must also actually be registered.
     */
    public UUID instanceTenant() {
        String configured = properties.getTenantId();
        List<UUID> active = activeTenants();
        if (configured != null && !configured.isBlank()) {
            UUID wanted = UUID.fromString(configured.trim());
            return active.contains(wanted) ? wanted : null;
        }
        if (active.isEmpty()) {
            return null;
        }
        if (active.size() > 1) {
            throw new IllegalStateException(
                    "several tenants are registered and fincore.identity.tenant-id does not name"
                            + " which one this instance authenticates");
        }
        return active.get(0);
    }
}
