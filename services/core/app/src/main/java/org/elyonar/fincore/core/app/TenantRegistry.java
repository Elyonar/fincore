package org.elyonar.fincore.core.app;

import java.util.List;
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
        offerTheStartingCurrencies(tenantId);
    }

    /**
     * Every registered tenant has its starting currencies, whatever path registered it.
     *
     * <p>{@link #register} seeding them was the right fix for the defect described below and did
     * not reach far enough, which only a real deployment revealed. {@code V2}'s seeding runs at
     * startup while {@code platform.tenants} is still empty, so it seeds nobody; and
     * {@code bootstrap/seed-registries.sh} registers tenants with {@code psql} INSERTs that never
     * enter this class, so {@link #register} does not run either. The exact symptom the currency
     * seeding exists to prevent — an empty dropdown, no internal account, no till, no customer
     * account, and nothing reporting a fault — was therefore still reachable, and was in fact what
     * a fresh deployment did.
     *
     * <p>Idempotent and additive, on every boot. It restores what is missing and never puts back a
     * currency an institution deliberately withdrew on the Currencies screen — that is what the
     * {@code ON CONFLICT DO NOTHING} in the statement below is protecting.
     *
     * @return how many tenants were converged, for the startup line that reports it
     */
    public int convergeStarterData() {
        List<UUID> tenants = provision.queryForList("SELECT id FROM platform.tenants", UUID.class);
        tenants.forEach(this::offerTheStartingCurrencies);
        return tenants.size();
    }

    /**
     * A new tenant starts with currencies to choose from.
     *
     * <p>The migration that created {@code platform.currencies} seeded every tenant that existed
     * when it ran, and nothing seeded the ones that arrived afterwards. So the platform worked and
     * the next institution provisioned onto it did not: an empty list means every currency dropdown
     * is empty, which means no internal account, no till and no customer account can be opened, on
     * a system whose own screens report nothing wrong.
     *
     * <p>Seeded rather than left to setup because it is not a decision anybody should have to make
     * before they can do anything. The list is trimmed on the Currencies screen, where withdrawing
     * one is a deliberate act rather than the consequence of an omission.
     *
     * <p>Exponents are ISO 4217's and match the ledger's registry, which is the authority. The three
     * that are not 2 are here on purpose: they are what a hardcoded two decimal places got wrong.
     */
    private void offerTheStartingCurrencies(UUID tenantId) {
        provision.update(
                """
                INSERT INTO platform.currencies (tenant_id, code, name, exponent)
                SELECT ?, c.code, c.name, c.exponent
                  FROM (VALUES
                        ('NGN', 'Nigerian naira',         2),
                        ('USD', 'US dollar',              2),
                        ('EUR', 'Euro',                   2),
                        ('GBP', 'Pound sterling',         2),
                        ('KES', 'Kenyan shilling',        2),
                        ('GHS', 'Ghanaian cedi',          2),
                        ('ZAR', 'South African rand',     2),
                        ('XOF', 'West African CFA franc', 0),
                        ('JPY', 'Japanese yen',           0),
                        ('KWD', 'Kuwaiti dinar',          3)
                  ) AS c(code, name, exponent)
                ON CONFLICT (tenant_id, code) DO NOTHING
                """,
                tenantId);
    }
}
