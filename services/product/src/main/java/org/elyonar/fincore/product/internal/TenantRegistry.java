package org.elyonar.fincore.product.internal;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.elyonar.fincore.product.api.ProductBeans;
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
            @Qualifier(ProductBeans.JDBC) JdbcTemplate read, @Qualifier("dataSource") DataSource owner) {
        this.read = read;
        this.provision = new JdbcTemplate(owner);
    }

    public boolean isActive(UUID tenantId) {
        String status = read.query(
                "SELECT status FROM product.tenants WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                tenantId);
        return "ACTIVE".equals(status);
    }

    /** Provisioning only, and deliberately not reachable from any request path. */
    public void register(UUID tenantId, String name, String createdBy) {
        provision.update(
                "INSERT INTO product.tenants (id, name, created_by) VALUES (?,?,?)"
                        + " ON CONFLICT (id) DO NOTHING",
                tenantId, name, createdBy);
        offerTheStartingProductTypes(tenantId);
    }

    /**
     * Every registered tenant has its starter vocabulary, whatever path registered it.
     *
     * <p>Seeding inside {@link #register} is necessary and was not sufficient, and the gap only
     * showed up on a real deployment. Two things conspire:
     *
     * <ol>
     *   <li>Flyway runs at service startup, when {@code product.tenants} is still empty — so the
     *       migration's {@code CROSS JOIN} over the tenants table inserts nothing, for anybody;
     *   <li>{@code bootstrap/seed-registries.sh} then registers tenants with {@code psql} INSERTs,
     *       which never enter this class at all, so {@link #register} does not run either.
     * </ol>
     *
     * <p>The result was an institution with no product types on a system reporting nothing wrong —
     * the same silent-empty-dropdown failure the seeding was added to prevent, arriving by a
     * different road. Converging here closes it for both roads at once, and keeps closing it for
     * whatever provisioning path arrives next.
     *
     * <p>Idempotent and additive, the same contract the identity manifest seeder converges on: it
     * runs on every boot, adds only what is missing, and never overwrites a vocabulary an
     * institution has edited.
     *
     * @return how many tenants were converged, for the startup line that reports it
     */
    public int convergeStarterData() {
        List<UUID> tenants = provision.queryForList("SELECT id FROM product.tenants", UUID.class);
        tenants.forEach(this::offerTheStartingProductTypes);
        return tenants.size();
    }

    /**
     * A new tenant starts with product types to choose from.
     *
     * <p>The same defect Core's currency registry already fixed, in the same shape. {@code V4}
     * seeded {@code product.product_types} by cross-joining the tenants table *as it stood when the
     * migration ran*, so every institution provisioned afterwards got an empty vocabulary — and an
     * empty vocabulary is an empty dropdown on the screen where a product is authored, on a system
     * whose own screens report nothing wrong.
     *
     * <p>Seeding on registration rather than in a migration is what makes it converge for the
     * hundredth tenant as well as the first. ADR 0021 is explicit that anything which breaks on a
     * second tenant in one instance is still a defect; this broke on every tenant after the first,
     * whichever instance it was in.
     *
     * <p>A starting position and not the law, exactly as {@code V4} says: the list is a tenant table
     * with a route rather than a CHECK constraint, and an institution edits it.
     */
    private void offerTheStartingProductTypes(UUID tenantId) {
        provision.update(
                """
                INSERT INTO product.product_types (tenant_id, code, name)
                SELECT ?, v.code, v.name
                  FROM (VALUES
                        ('SAVINGS',       'Savings'),
                        ('CURRENT',       'Current'),
                        ('FIXED_DEPOSIT', 'Fixed deposit'),
                        ('TARGET',        'Target savings')
                   ) AS v(code, name)
                ON CONFLICT (tenant_id, code) DO NOTHING
                """,
                tenantId);
    }
}
