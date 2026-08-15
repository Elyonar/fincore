package org.elyonar.fincore.customer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.elyonar.fincore.customer.internal.TenantRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The same defect as {@code product}'s starter vocabulary, and the one with the sharper edge: a
 * customer's KYC tier is what the product evaluator prices and limits against, so an institution
 * with no tiers cannot onboard its first customer at all.
 *
 * <p>{@code V3} cross-joined the tenants table as it stood when the migration ran. This asserts the
 * seeding now happens on registration, for a tenant that did not exist then.
 */
@SpringBootTest
@DisplayName("starter KYC tiers — an institution provisioned after the migration can still onboard")
class StarterKycTiersTest {

    private static final List<String> EXPECTED = List.of("TIER_1", "TIER_2", "TIER_3");

    @Autowired private TenantRegistry tenants;

    /**
     * The owner connection: this asserts what provisioning wrote, and provisioning runs as the
     * owner. Reading through the application role would assert that RLS works, which is another
     * test's job.
     */
    private JdbcTemplate customerDb;

    @Autowired
    void useTheOwnerConnection(@Qualifier("dataSource") DataSource owner) {
        this.customerDb = new JdbcTemplate(owner);
    }

    @Test
    @DisplayName("a tenant registered now starts with tiers to onboard into")
    void registration_seeds_the_tiers() {
        UUID tenantId = UUID.randomUUID();
        tenants.register(tenantId, "Late Arrival MFB", "test");

        assertThat(codesFor(tenantId)).containsExactlyInAnyOrderElementsOf(EXPECTED);
    }

    @Test
    @DisplayName("re-registering never overwrites tiers the institution has renamed")
    void registration_converges_rather_than_resets() {
        UUID tenantId = UUID.randomUUID();
        tenants.register(tenantId, "Opinionated MFB", "test");

        // TIER_1..3 is Nigeria's vocabulary. An institution elsewhere renames it, and provisioning
        // running a second time must not put the Nigerian name back.
        customerDb.update(
                "UPDATE customer.kyc_tiers SET name = 'Basic' WHERE tenant_id = ? AND code = 'TIER_1'",
                tenantId);

        tenants.register(tenantId, "Opinionated MFB", "test");

        String name = customerDb.queryForObject(
                "SELECT name FROM customer.kyc_tiers WHERE tenant_id = ? AND code = 'TIER_1'",
                String.class,
                tenantId);
        assertThat(name).isEqualTo("Basic");
    }

    @Test
    @DisplayName("a tenant registered by the psql provisioning script is converged at the next boot")
    void convergence_catches_the_tenant_that_bypassed_registration() {
        // What bootstrap/seed-registries.sh actually does — an INSERT from bash, never entering
        // this service's Java at all. Seeding on registration cannot reach it, and the institution
        // it produces cannot onboard anybody because it has no tier to put them in.
        UUID tenantId = UUID.randomUUID();
        customerDb.update(
                "INSERT INTO customer.tenants (id, name, created_by) VALUES (?,?,'seed-registries.sh')",
                tenantId, "Bypassed MFB");
        assertThat(codesFor(tenantId)).isEmpty();

        tenants.convergeStarterData();

        assertThat(codesFor(tenantId)).containsExactlyInAnyOrderElementsOf(EXPECTED);
    }

    private List<String> codesFor(UUID tenantId) {
        return customerDb.queryForList(
                "SELECT code FROM customer.kyc_tiers WHERE tenant_id = ?", String.class, tenantId);
    }
}
