package org.elyonar.fincore.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.elyonar.fincore.identity.api.IdentityErrors.AuthFailed;
import org.elyonar.fincore.identity.api.IdentityErrors.PasswordPolicy;
import org.elyonar.fincore.identity.api.IdentityErrors.TokenInvalid;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The security core of the swap, exercised through the real service beans against real PostgreSQL.
 * These are the behaviours ADR 0018 is only trustworthy if it has: the lifecycle a seeded admin
 * actually walks (temporary credential → forced change → tokens), and the adversarial properties
 * the threat model names — one voice for every failure, theft detection on refresh reuse, and a
 * silent lockout that a correct guess cannot distinguish from a wrong one.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("login — the lifecycle a seeded admin walks, and the ways it refuses")
class LoginFlowTest {

    @Autowired private LoginService login;
    @Autowired private Passwords passwords;
    @Autowired private Tenants tenants;
    @Autowired private Tx tx;

    @Autowired
    @Qualifier("dataSource")
    private javax.sql.DataSource ownerDataSource;

    private UUID tenant;
    private String username;

    @BeforeEach
    void seedAnInstitutionWithOneAdmin() {
        tenant = UUID.randomUUID();
        username = "ada.admin";
        // Register the single tenant so instanceTenant() resolves without ambiguity, then plant a
        // user with a known temporary credential — the exact shape ManifestSeeder produces.
        tenants.register(tenant, "Acme MFB", "test");
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource);
        UUID userId = UUID.randomUUID();
        tx.inTenant(tenant, () -> {
            tx.jdbc().update(
                    "INSERT INTO identity.role_permissions (tenant_id, role_name, permission)"
                            + " VALUES (?, 'job:admin', 'customers:read') ON CONFLICT DO NOTHING",
                    tenant);
            tx.jdbc().update(
                    "INSERT INTO identity.users (tenant_id, id, username, email, first_name, last_name,"
                            + " credential_temporary, created_by, created_via)"
                            + " VALUES (?,?,?,?,?,?,TRUE,'test','test')",
                    tenant, userId, username, "a@acme.test", "Ada", "Okonkwo");
            tx.jdbc().update(
                    "INSERT INTO identity.user_roles (tenant_id, user_id, role_name)"
                            + " VALUES (?,?, 'job:admin')",
                    tenant, userId);
            tx.jdbc().update(
                    "INSERT INTO identity.credentials (tenant_id, user_id, password_hash) VALUES (?,?,?)",
                    tenant, userId, passwords.hash("temp-Password-01"));
            return null;
        });
        // Isolate throttle counters between tests — a shared PostgreSQL instance carries them over.
        owner.update("DELETE FROM identity.login_throttle WHERE tenant_id = ?", tenant);
    }

    @Test
    @DisplayName("a temporary credential yields an action grant, not tokens; the change unlocks login")
    void forcedChangeThenLogin() {
        LoginService.LoginOutcome first = login.login(username, "temp-Password-01", "1.2.3.4", "fincore-web");
        assertThat(first).isInstanceOf(Obliged.class);
        String actionToken = ((Obliged) first).action().actionToken();
        assertThat(((Obliged) first).action().reason()).isEqualTo("PASSWORD_CHANGE_REQUIRED");

        login.changePassword(actionToken, null, null, "brand-New-Password-9", "1.2.3.4");

        LoginService.LoginOutcome second = login.login(username, "brand-New-Password-9", "1.2.3.4", "fincore-web");
        assertThat(second).isInstanceOf(Granted.class);
        LoginService.TokenPair pair = ((Granted) second).tokens();
        assertThat(pair.accessToken()).isNotBlank();
        assertThat(pair.refreshToken()).isNotBlank();
    }

    @Test
    @DisplayName("the old temporary credential no longer works once changed")
    void temporaryCredentialDiesOnChange() {
        String action = ((Obliged) login.login(username, "temp-Password-01", "1.2.3.4", "web")).action().actionToken();
        login.changePassword(action, null, null, "brand-New-Password-9", "1.2.3.4");
        assertThatThrownBy(() -> login.login(username, "temp-Password-01", "1.2.3.4", "web"))
                .isInstanceOf(AuthFailed.class);
    }

    @Test
    @DisplayName("unknown user and wrong password refuse identically — one code, no reason")
    void oneVoiceForEveryFailure() {
        assertThatThrownBy(() -> login.login("nobody", "whatever", "1.2.3.4", "web"))
                .isInstanceOf(AuthFailed.class);
        assertThatThrownBy(() -> login.login(username, "wrong-password", "1.2.3.4", "web"))
                .isInstanceOf(AuthFailed.class);
    }

    @Test
    @DisplayName("a policy-violating new password is refused")
    void passwordPolicyEnforced() {
        String action = ((Obliged) login.login(username, "temp-Password-01", "1.2.3.4", "web")).action().actionToken();
        assertThatThrownBy(() -> login.changePassword(action, null, null, "short", "1.2.3.4"))
                .isInstanceOf(PasswordPolicy.class);
    }

    @Test
    @DisplayName("refresh rotates; reusing a rotated token kills the whole family")
    void refreshRotationDetectsTheft() {
        String action = ((Obliged) login.login(username, "temp-Password-01", "1.2.3.4", "web")).action().actionToken();
        login.changePassword(action, null, null, "brand-New-Password-9", "1.2.3.4");
        LoginService.TokenPair pair = ((Granted) login.login(username, "brand-New-Password-9", "1.2.3.4", "web")).tokens();

        LoginService.TokenPair rotated = login.refresh(pair.refreshToken(), "1.2.3.4", "web");
        assertThat(rotated.refreshToken()).isNotEqualTo(pair.refreshToken());

        // Replaying the now-rotated original is the theft signal: it revokes the family, so even the
        // legitimately rotated token stops working afterwards.
        assertThatThrownBy(() -> login.refresh(pair.refreshToken(), "9.9.9.9", "web"))
                .isInstanceOf(TokenInvalid.class);
        assertThatThrownBy(() -> login.refresh(rotated.refreshToken(), "1.2.3.4", "web"))
                .isInstanceOf(TokenInvalid.class);
    }

    @Test
    @DisplayName("a correct password during a lockout refuses the same way a wrong one does")
    void lockoutIsSilentAndNotAnOracle() {
        // Drive the account past the lock threshold with wrong guesses.
        for (int i = 0; i < 6; i++) {
            try {
                login.login(username, "wrong-" + i, "1.2.3.4", "web");
            } catch (AuthFailed ignored) {
                // expected
            }
        }
        // The right credential now, still locked: it must not read differently from a wrong one.
        assertThatThrownBy(() -> login.login(username, "temp-Password-01", "1.2.3.4", "web"))
                .isInstanceOf(AuthFailed.class);
    }
}
