package org.elyonar.fincore.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.elyonar.fincore.identity.api.IdentityErrors.AuthFailed;
import org.elyonar.fincore.identity.api.IdentityErrors.PasswordPolicy;
import org.elyonar.fincore.identity.auth.LoginService;
import org.elyonar.fincore.identity.auth.LoginService.Granted;
import org.elyonar.fincore.identity.auth.LoginService.Obliged;
import org.elyonar.fincore.identity.auth.Passwords;
import org.elyonar.fincore.identity.internal.Tenants;
import org.elyonar.fincore.identity.internal.Tx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * The security core of the swap, exercised through the real service beans against real PostgreSQL:
 * the lifecycle a seeded admin walks (temporary credential → forced change → tokens), and the
 * adversarial properties the threat model names — one voice for every failure, theft detection on
 * refresh reuse, and a silent lockout a correct guess cannot distinguish from a wrong one.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("login — the lifecycle a seeded admin walks, and the ways it refuses")
class LoginFlowTest {

    // Must equal fincore.identity.tenant-id in application-test.yml, so instanceTenant() resolves
    // to this tenant rather than the multi-tenant ambiguity a shared test database would create.
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String TEMP = "temp-Password-01";

    @Autowired private LoginService login;
    @Autowired private Passwords passwords;
    @Autowired private Tenants tenants;
    @Autowired private Tx tx;

    private String username;
    private String source;

    @BeforeEach
    void seedAFreshAdmin() {
        tenants.register(TENANT, "Acme MFB", "test"); // idempotent — ON CONFLICT DO NOTHING
        // A distinct username and source per test, because the schema grants no DELETE on users
        // (by design) so rows accumulate across methods; distinct keys keep each test independent
        // without tearing anything down.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        username = "ada." + suffix;
        source = "10.0." + Math.abs(suffix.hashCode() % 250) + ".1";
        UUID userId = UUID.randomUUID();
        tx.inTenant(TENANT, () -> {
            tx.jdbc().update(
                    "INSERT INTO auth.role_permissions (tenant_id, role_name, permission)"
                            + " VALUES (?, 'job:admin', 'customers:read') ON CONFLICT DO NOTHING",
                    TENANT);
            tx.jdbc().update(
                    "INSERT INTO auth.users (tenant_id, id, username, email, first_name, last_name,"
                            + " credential_temporary, created_by, created_via)"
                            + " VALUES (?,?,?,?,?,?,TRUE,'test','test')",
                    TENANT, userId, username, username + "@acme.test", "Ada", "Okonkwo");
            tx.jdbc().update(
                    "INSERT INTO auth.user_roles (tenant_id, user_id, role_name)"
                            + " VALUES (?,?, 'job:admin')",
                    TENANT, userId);
            tx.jdbc().update(
                    "INSERT INTO auth.credentials (tenant_id, user_id, password_hash) VALUES (?,?,?)",
                    TENANT, userId, passwords.hash(TEMP));
            return null;
        });
    }

    @Test
    @DisplayName("a temporary credential yields an action grant, not tokens; the change unlocks login")
    void forcedChangeThenLogin() {
        LoginService.LoginOutcome first = login.login(username, TEMP, source, "fincore-web");
        assertThat(first).isInstanceOf(Obliged.class);
        assertThat(((Obliged) first).action().reason()).isEqualTo("PASSWORD_CHANGE_REQUIRED");

        login.changePassword(((Obliged) first).action().actionToken(), null, null, "brand-New-Password-9", source);

        LoginService.LoginOutcome second = login.login(username, "brand-New-Password-9", source, "fincore-web");
        assertThat(second).isInstanceOf(Granted.class);
        LoginService.TokenPair pair = ((Granted) second).tokens();
        assertThat(pair.accessToken()).isNotBlank();
        assertThat(pair.refreshToken()).isNotBlank();
    }

    @Test
    @DisplayName("the old temporary credential no longer works once changed")
    void temporaryCredentialDiesOnChange() {
        String action = ((Obliged) login.login(username, TEMP, source, "web")).action().actionToken();
        login.changePassword(action, null, null, "brand-New-Password-9", source);
        assertThatThrownBy(() -> login.login(username, TEMP, source, "web")).isInstanceOf(AuthFailed.class);
    }

    @Test
    @DisplayName("unknown user and wrong password refuse identically — one code, no reason")
    void oneVoiceForEveryFailure() {
        assertThatThrownBy(() -> login.login("nobody-" + username, "whatever", source, "web"))
                .isInstanceOf(AuthFailed.class);
        assertThatThrownBy(() -> login.login(username, "wrong-password", source, "web"))
                .isInstanceOf(AuthFailed.class);
    }

    @Test
    @DisplayName("a policy-violating new password is refused")
    void passwordPolicyEnforced() {
        String action = ((Obliged) login.login(username, TEMP, source, "web")).action().actionToken();
        assertThatThrownBy(() -> login.changePassword(action, null, null, "short", source))
                .isInstanceOf(PasswordPolicy.class);
    }

    @Test
    @DisplayName("refresh rotates; reusing a rotated token kills the whole family")
    void refreshRotationDetectsTheft() {
        String action = ((Obliged) login.login(username, TEMP, source, "web")).action().actionToken();
        login.changePassword(action, null, null, "brand-New-Password-9", source);
        LoginService.TokenPair pair = ((Granted) login.login(username, "brand-New-Password-9", source, "web")).tokens();

        LoginService.TokenPair rotated = login.refresh(pair.refreshToken(), source, "web");
        assertThat(rotated.refreshToken()).isNotEqualTo(pair.refreshToken());

        // Replaying the rotated original is the theft signal: it revokes the family, so even the
        // legitimately rotated token stops working afterwards.
        assertThatThrownBy(() -> login.refresh(pair.refreshToken(), source, "web"))
                .isInstanceOf(org.elyonar.fincore.identity.api.IdentityErrors.TokenInvalid.class);
        assertThatThrownBy(() -> login.refresh(rotated.refreshToken(), source, "web"))
                .isInstanceOf(org.elyonar.fincore.identity.api.IdentityErrors.TokenInvalid.class);
    }

    @Test
    @DisplayName("a correct password during a lockout refuses the same way a wrong one does")
    void lockoutIsSilentAndNotAnOracle() {
        for (int i = 0; i < 6; i++) {
            try {
                login.login(username, "wrong-" + i, source, "web");
            } catch (AuthFailed ignored) {
                // expected
            }
        }
        assertThatThrownBy(() -> login.login(username, TEMP, source, "web")).isInstanceOf(AuthFailed.class);
    }
}
