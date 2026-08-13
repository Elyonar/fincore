package org.elyonar.fincore.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.identity.auth.Passwords;
import org.elyonar.fincore.identity.internal.Tenants;
import org.elyonar.fincore.identity.directory.Directory;
import org.elyonar.fincore.identity.internal.Tx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Resetting somebody's credential cannot hand you authority you do not have.
 *
 * <p>Guardrail 1 (ADR 0017) says an administrator may not compose or grant a permission they do
 * not already hold, and it was enforced on both — and nowhere on the credential reset, which made
 * the other two decoration. The path was complete and quiet:
 *
 * <ol>
 *   <li>An administrator holding {@code users:manage} cannot grant themselves {@code job:teller};
 *       the role assignment refuses it, correctly.
 *   <li>The same administrator resets the super-administrator's credential — offered, and until
 *       now allowed.
 *   <li>They sign in as the super-administrator and grant themselves anything at all.
 * </ol>
 *
 * <p>Taking over an account is not weaker than being granted what it holds. It is the same grant
 * reached through the credential instead of the role, so it takes the same check.
 */
@SpringBootTest
@ActiveProfiles("test")
class CredentialResetIsNotAnEscalationTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired private Directory directory;
    @Autowired private Passwords passwords;
    @Autowired private Tenants tenants;
    @Autowired private Tx tx;

    private UUID admin;
    private UUID superAdmin;
    private UUID teller;
    private String suffix;

    @BeforeEach
    void seedThreePeople() {
        tenants.register(TENANT, "Acme MFB", "test");
        suffix = UUID.randomUUID().toString().substring(0, 8);

        // Roles named per test rather than the platform's `job:*` templates. The templates are
        // seeded by the service and shared across every test in this database, so asserting
        // "holds strictly less than" against them would be asserting against whatever the seed
        // happens to contain today — a test that passes for a reason it does not state.
        //
        // Here the shape is the whole point and is written down: the administrator runs the
        // institution and moves no cash; the senior holds everything the administrator does and
        // `cash:transact` besides; the teller holds only the cash permission.
        grant("role:admin-" + suffix, "users:manage", "users:read", "org:read");
        grant("role:senior-" + suffix, "users:manage", "users:read", "org:read", "cash:transact");
        grant("role:teller-" + suffix, "cash:transact");

        admin = person("kemi." + suffix, "role:admin-" + suffix);
        superAdmin = person("ada." + suffix, "role:senior-" + suffix);
        teller = person("ngozi." + suffix, "role:teller-" + suffix);
    }

    @Test
    @DisplayName("an administrator cannot reset a credential for somebody who outranks them")
    void resetIsRefusedUpwards() {
        assertThatThrownBy(() -> reset(admin, superAdmin))
                .hasMessageContaining("PERMISSION_NOT_HELD_BY_GRANTOR");

        // And for the same reason, not sideways onto a permission they lack either: a teller holds
        // `cash:transact` and this administrator does not, so taking that account is taking it.
        assertThatThrownBy(() -> reset(admin, teller))
                .hasMessageContaining("PERMISSION_NOT_HELD_BY_GRANTOR");
    }

    @Test
    @DisplayName("a super-administrator may reset anybody, because they already hold everything")
    void resetIsAllowedDownwards() {
        assertThat(reset(superAdmin, admin)).startsWith("tmp_");
        assertThat(reset(superAdmin, teller)).startsWith("tmp_");
    }

    @Test
    @DisplayName("resetting your own credential is always allowed")
    void resetOfSelfIsAllowed() {
        // Refusing this would lock somebody out of their own account for being senior, and it
        // confers nothing: you cannot escalate to yourself.
        assertThat(reset(admin, admin)).startsWith("tmp_");
        assertThat(reset(superAdmin, superAdmin)).startsWith("tmp_");
    }

    @Test
    @DisplayName("a peer holding exactly the same permissions may still be reset")
    void resetOfAnEqualIsAllowed() {
        // The check is "holds something I do not", never "is a different person" — two
        // administrators covering for each other is the ordinary case and must keep working.
        UUID peer = person("bola." + suffix, "role:admin-" + suffix);
        assertThat(reset(admin, peer)).startsWith("tmp_");
    }

    private String reset(UUID actor, UUID target) {
        return tx.inTenant(
                TENANT,
                () ->
                        directory.resetPassword(
                                TENANT, target, administrator(actor), "10.0.0.1"));
    }

    private Directory.Administrator administrator(UUID userId) {
        return new Directory.Administrator(userId, "user:test", "test");
    }

    private void grant(String role, String... permissions) {
        tx.inTenant(
                TENANT,
                () -> {
                    tx.jdbc()
                            .update(
                                    "INSERT INTO auth.roles (tenant_id, name, template, created_by, created_via)"
                                            + " VALUES (?,?,TRUE,'test','test') ON CONFLICT DO NOTHING",
                                    TENANT, role);
                    for (String permission : List.of(permissions)) {
                        tx.jdbc()
                                .update(
                                        "INSERT INTO auth.role_permissions (tenant_id, role_name, permission)"
                                                + " VALUES (?,?,?) ON CONFLICT DO NOTHING",
                                        TENANT, role, permission);
                    }
                    return null;
                });
    }

    private UUID person(String username, String role) {
        UUID id = UUID.randomUUID();
        tx.inTenant(
                TENANT,
                () -> {
                    tx.jdbc()
                            .update(
                                    "INSERT INTO auth.users (tenant_id, id, username, email, first_name,"
                                            + " last_name, credential_temporary, created_by, created_via)"
                                            + " VALUES (?,?,?,?,?,?,FALSE,'test','test')",
                                    TENANT, id, username, username + "@acme.test", "Test", "Person");
                    tx.jdbc()
                            .update(
                                    "INSERT INTO auth.user_roles (tenant_id, user_id, role_name) VALUES (?,?,?)",
                                    TENANT, id, role);
                    tx.jdbc()
                            .update(
                                    "INSERT INTO auth.credentials (tenant_id, user_id, password_hash) VALUES (?,?,?)",
                                    TENANT, id, passwords.hash("temp-Password-01"));
                    return null;
                });
        return id;
    }
}
