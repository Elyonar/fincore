package org.elyonar.fincore.customer.internal;

import java.util.List;
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
        offerTheStartingKycTiers(tenantId);
    }

    /**
     * Every registered tenant has its starter tiers, whatever path registered it.
     *
     * <p>Seeding inside {@link #register} was necessary and not sufficient, and only a real
     * deployment showed why. The migration's {@code CROSS JOIN} runs at startup while
     * {@code customer.tenants} is still empty, so it seeds nobody; and
     * {@code bootstrap/seed-registries.sh} registers tenants with {@code psql} INSERTs that never
     * enter this class, so {@link #register} never runs either. An institution ended up with no
     * tier to onboard anybody into — and a tier is what the product evaluator prices against.
     *
     * <p>Idempotent and additive, on every boot, the same contract the identity manifest seeder
     * converges on. It adds what is missing and never overwrites a tier an institution renamed.
     *
     * @return how many tenants were converged, for the startup line that reports it
     */
    public int convergeStarterData() {
        List<UUID> tenants = provision.queryForList("SELECT id FROM customer.tenants", UUID.class);
        tenants.forEach(this::offerTheStartingKycTiers);
        return tenants.size();
    }

    /**
     * A new tenant starts with KYC tiers to place a customer in.
     *
     * <p>The currencies bug again, and here it bites hardest. {@code V3} seeded
     * {@code customer.kyc_tiers} by cross-joining the tenants table as it stood when the migration
     * ran, so an institution provisioned afterwards has no tier to onboard anybody into — and a
     * customer's tier is what the product evaluator prices and limits against. An empty tier list is
     * an institution that cannot take its first customer.
     *
     * <p>{@code TIER_1..3} is Nigeria's answer and wrong everywhere else, which is why this is a
     * tenant table with a route rather than a CHECK constraint. Seeded as a starting position, not
     * as the law: an institution renames, reorders or retires these from its own screens, and
     * {@code ON CONFLICT DO NOTHING} means a re-registration never overwrites what it chose.
     */
    private void offerTheStartingKycTiers(UUID tenantId) {
        provision.update(
                """
                INSERT INTO customer.kyc_tiers (tenant_id, code, name, requires, rank)
                SELECT ?, v.code, v.name, v.requires, v.rank
                  FROM (VALUES
                        ('TIER_1', 'Tier 1', 'A name and a phone number. Lowest ceilings.', 1),
                        ('TIER_2', 'Tier 2', 'Identity verified against a document.',       2),
                        ('TIER_3', 'Tier 3', 'Identity and address verified. Highest ceilings.', 3)
                   ) AS v(code, name, requires, rank)
                ON CONFLICT (tenant_id, code) DO NOTHING
                """,
                tenantId);
    }
}
