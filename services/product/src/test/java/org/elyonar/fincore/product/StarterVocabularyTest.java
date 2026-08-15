package org.elyonar.fincore.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.elyonar.fincore.product.internal.TenantRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The currencies bug, in this service, caught this time.
 *
 * <p>{@code V4} seeded the product types by cross-joining the tenants table as it stood when the
 * migration ran. Every institution provisioned after that got nothing, and the symptom was an empty
 * dropdown rather than an error — a system reporting nothing wrong while being unusable. Core fixed
 * the identical defect in its currency registry by seeding inside {@code register()}; this asserts
 * that product does the same, for a tenant that could not possibly have existed at migration time.
 *
 * <p>The test that matters is the second one. Anybody can make the first tenant work.
 */
@SpringBootTest
@DisplayName("starter vocabulary — a tenant registered today, not when the migration ran")
class StarterVocabularyTest {

    private static final List<String> EXPECTED = List.of("SAVINGS", "CURRENT", "FIXED_DEPOSIT", "TARGET");

    @Autowired private TenantRegistry tenants;

    /**
     * The owner connection, not {@code productJdbcTemplate}.
     *
     * <p>This asserts what *provisioning* wrote, and provisioning runs as the owner — the same
     * split {@code TenantRegistry} itself makes. Reading back through the application role would
     * need a tenant context set by hand, which would be a test asserting that RLS works (it does,
     * and other tests prove it) rather than that the vocabulary is there.
     */
    private JdbcTemplate productDb;

    @Autowired
    void useTheOwnerConnection(@Qualifier("dataSource") DataSource owner) {
        this.productDb = new JdbcTemplate(owner);
    }

    @Test
    @DisplayName("a tenant registered now starts with product types to author against")
    void registration_seeds_the_vocabulary() {
        UUID tenantId = UUID.randomUUID();
        tenants.register(tenantId, "Late Arrival MFB", "test");

        assertThat(codesFor(tenantId)).containsExactlyInAnyOrderElementsOf(EXPECTED);
    }

    @Test
    @DisplayName("re-registering never overwrites a vocabulary the institution has edited")
    void registration_converges_rather_than_resets() {
        UUID tenantId = UUID.randomUUID();
        tenants.register(tenantId, "Opinionated MFB", "test");

        // An institution retires a type it does not offer and adds one the platform never guessed.
        // A second registration — a re-run of provisioning, a replayed call — must leave both alone.
        productDb.update(
                "UPDATE product.product_types SET active = FALSE WHERE tenant_id = ? AND code = 'TARGET'",
                tenantId);
        productDb.update(
                "INSERT INTO product.product_types (tenant_id, code, name) VALUES (?, 'ESUSU', 'Esusu')",
                tenantId);

        tenants.register(tenantId, "Opinionated MFB", "test");

        assertThat(codesFor(tenantId)).contains("ESUSU");
        Boolean stillRetired = productDb.queryForObject(
                "SELECT active FROM product.product_types WHERE tenant_id = ? AND code = 'TARGET'",
                Boolean.class,
                tenantId);
        assertThat(stillRetired).isFalse();
    }

    @Test
    @DisplayName("a tenant registered by the psql provisioning script is converged at the next boot")
    void convergence_catches_the_tenant_that_bypassed_registration() {
        // Exactly what bootstrap/seed-registries.sh does: an INSERT straight into the registry, by
        // a bash script holding a psql connection. It never enters TenantRegistry, so seeding on
        // registration cannot help it — which is how a deployment ended up with two institutions
        // and an empty vocabulary for both, on a stack whose every log line said it was healthy.
        UUID tenantId = UUID.randomUUID();
        productDb.update(
                "INSERT INTO product.tenants (id, name, created_by) VALUES (?,?,'seed-registries.sh')",
                tenantId, "Bypassed MFB");
        assertThat(codesFor(tenantId)).isEmpty();

        tenants.convergeStarterData();

        assertThat(codesFor(tenantId)).containsExactlyInAnyOrderElementsOf(EXPECTED);
    }

    private List<String> codesFor(UUID tenantId) {
        return productDb.queryForList(
                "SELECT code FROM product.product_types WHERE tenant_id = ?", String.class, tenantId);
    }
}
