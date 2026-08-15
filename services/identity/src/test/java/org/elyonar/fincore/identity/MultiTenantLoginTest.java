package org.elyonar.fincore.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.elyonar.fincore.identity.api.IdentityErrors.AuthFailed;
import org.elyonar.fincore.identity.auth.LoginService;
import org.elyonar.fincore.identity.auth.LoginService.Granted;
import org.elyonar.fincore.identity.auth.Passwords;
import org.elyonar.fincore.identity.internal.Tenants;
import org.elyonar.fincore.identity.internal.Tx;
import org.elyonar.fincore.identity.token.LocalVerifier;
import org.elyonar.fincore.identity.token.TokenMinter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * The defect ADR 0023 names, and its fix: an instance holding more than one institution could not
 * authenticate any but one of them.
 *
 * <p>The case that matters is the one a shared name creates. Two institutions on one sandbox
 * instance both have an {@code adaokonkwo}, both with a valid credential, and nothing in the
 * request said which bank was meant. Before the realm, the second institution's staff could not log
 * in at all — row-level security hid their user and the attempt refused as an unknown username. The
 * failure this guards against now is worse than that one and quieter: resolving to the *wrong*
 * institution and granting a token that works.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("realm — one instance, several institutions, and the same username in two of them")
class MultiTenantLoginTest {

    /** The tenant {@code fincore.identity.tenant-id} names in application-test.yml. */
    private static final UUID INSTANCE_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID SECOND_TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String PASSWORD = "shared-Password-42";

    @Autowired private LoginService login;
    @Autowired private Passwords passwords;
    @Autowired private Tenants tenants;
    @Autowired private LocalVerifier verifier;
    @Autowired private Tx tx;

    private String sharedUsername;
    private String firstRealm;
    private String secondRealm;
    private UUID firstUserId;
    private UUID secondUserId;
    private String source;

    @BeforeEach
    void twoInstitutionsOnOneInstance() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        firstRealm = "acme-" + suffix;
        secondRealm = "zenith-" + suffix;
        // One username, two institutions. This is the whole point of the test: the pair
        // (realm, username) is what identifies a person, and the username alone never did.
        sharedUsername = "ada.okonkwo." + suffix;
        source = "10.1." + Math.abs(suffix.hashCode() % 250) + ".1";

        // A realm is convergent, so re-registering the instance tenant under a fresh realm each run
        // is safe and keeps the tests independent of each other's leftovers.
        tenants.register(INSTANCE_TENANT, "Acme MFB", "test", profile(INSTANCE_TENANT, firstRealm, "Acme MFB"));
        tenants.register(SECOND_TENANT, "Zenith MFB", "test", profile(SECOND_TENANT, secondRealm, "Zenith MFB"));

        firstUserId = seedUser(INSTANCE_TENANT);
        secondUserId = seedUser(SECOND_TENANT);
    }

    @Test
    @DisplayName("the same username in two institutions resolves by realm, to two different people")
    void realm_picks_the_institution() {
        UUID first = subjectOf(grant(firstRealm));
        UUID second = subjectOf(grant(secondRealm));

        assertThat(first).isEqualTo(firstUserId);
        assertThat(second).isEqualTo(secondUserId);
        // The assertion the wrong-bank bug would fail. Two tokens, one username, two subjects.
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("the token carries the institution the realm named, not the instance's default")
    void token_is_scoped_to_the_named_tenant() {
        var claims = claimsOf(grant(secondRealm));
        assertThat(claims.getClaim(TokenMinter.CLAIM_TENANT))
                .asString()
                .isEqualTo(SECOND_TENANT.toString());
    }

    @Test
    @DisplayName("an unknown realm refuses in the one voice, and never says the institution is unknown")
    void unknown_realm_is_indistinguishable_from_a_bad_credential() {
        // A distinguishable refusal here would enumerate which institutions bank on this platform,
        // to anyone with a browser and no credential at all.
        assertThatThrownBy(() -> login.login("no-such-bank", sharedUsername, PASSWORD, source, "web"))
                .isInstanceOf(AuthFailed.class);
        assertThatThrownBy(() -> login.login(firstRealm, sharedUsername, "wrong-password", source, "web"))
                .isInstanceOf(AuthFailed.class);
    }

    @Test
    @DisplayName("a realm belonging to one institution never unlocks another's credential")
    void a_credential_does_not_travel_between_institutions() {
        tx.inTenant(SECOND_TENANT, () -> {
            tx.jdbc().update(
                    "UPDATE auth.credentials SET password_hash = ?"
                            + " WHERE tenant_id = ? AND user_id = ?",
                    passwords.hash("a-Different-Password-9"), SECOND_TENANT, secondUserId);
            return null;
        });
        // The first institution's password, offered to the second institution's realm.
        assertThatThrownBy(() -> login.login(secondRealm, sharedUsername, PASSWORD, source, "web"))
                .isInstanceOf(AuthFailed.class);
        assertThatCode(() -> login.login(firstRealm, sharedUsername, PASSWORD, source, "web"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("omitting the realm still works where the instance names its tenant")
    void the_single_tenant_deployment_is_unchanged() {
        // ADR 0021's default posture, and the reason realm is optional rather than required: an
        // instance serving one institution has nothing to disambiguate, and its clients send
        // nothing new. Here fincore.identity.tenant-id names it even though two are registered.
        assertThat(subjectOf(grant(null))).isEqualTo(firstUserId);
    }

    // ---- helpers ---------------------------------------------------------------------------

    private LoginService.TokenPair grant(String realm) {
        return ((Granted) login.login(realm, sharedUsername, PASSWORD, source, "web")).tokens();
    }

    private com.nimbusds.jwt.JWTClaimsSet claimsOf(LoginService.TokenPair pair) {
        return verifier.verify(pair.accessToken()).orElseThrow();
    }

    private UUID subjectOf(LoginService.TokenPair pair) {
        return UUID.fromString(claimsOf(pair).getSubject());
    }

    private static Tenants.Profile profile(UUID id, String realm, String name) {
        return new Tenants.Profile(id, realm, name, name, "NG", "MFB", "https://" + realm + ".test", null);
    }

    private UUID seedUser(UUID tenantId) {
        UUID userId = UUID.randomUUID();
        tx.inTenant(tenantId, () -> {
            tx.jdbc().update(
                    "INSERT INTO auth.role_permissions (tenant_id, role_name, permission)"
                            + " VALUES (?, 'job:admin', 'customers:read') ON CONFLICT DO NOTHING",
                    tenantId);
            tx.jdbc().update(
                    "INSERT INTO auth.users (tenant_id, id, username, email, first_name, last_name,"
                            + " credential_temporary, created_by, created_via)"
                            + " VALUES (?,?,?,?,?,?,FALSE,'test','test')",
                    tenantId, userId, sharedUsername, sharedUsername + "@test.invalid", "Ada", "Okonkwo");
            tx.jdbc().update(
                    "INSERT INTO auth.user_roles (tenant_id, user_id, role_name) VALUES (?,?, 'job:admin')",
                    tenantId, userId);
            tx.jdbc().update(
                    "INSERT INTO auth.credentials (tenant_id, user_id, password_hash) VALUES (?,?,?)",
                    tenantId, userId, passwords.hash(PASSWORD));
            return null;
        });
        return userId;
    }
}
