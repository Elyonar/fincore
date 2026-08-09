package org.elyonar.fincore.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.auth.IdentityContext;
import org.elyonar.fincore.auth.NotAuthenticatedException;
import org.elyonar.fincore.auth.spring.AuthProperties;
import org.elyonar.fincore.auth.spring.JwtIdentityResolver;
import org.elyonar.fincore.identity.internal.IdentityProperties;
import org.elyonar.fincore.identity.token.KeyRing;
import org.elyonar.fincore.identity.token.TokenMinter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * The acceptance test of ADR 0018: a token minted by this service resolves through the unchanged
 * {@code libs/auth} resolver into exactly the {@code IdentityContext} the platform builds today —
 * same tenant, same principal, same permissions, same units. If this passes, every one of the 46
 * authorized handlers, the ledger's caller rules and RLS keep working without a line changing.
 *
 * <p>No Spring context and no database: this is purely the contract between the minting side and
 * the verifying side, which is the only thing the swap is allowed to touch.
 */
@DisplayName("token — minted here, resolved by libs/auth, identical context (ADR 0018)")
class TokenParityTest {

    private static final String ISSUER = "https://identity.acme.test";
    private static KeyRing keys;
    private static TokenMinter minter;
    private static JwtIdentityResolver resolver;

    @BeforeAll
    static void setUp() throws Exception {
        IdentityProperties props = new IdentityProperties();
        props.setIssuer(ISSUER);
        // No signing key configured + a sanctioned "test" profile => ephemeral key, exactly the
        // dev path, which is all this contract test needs.
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        keys = new KeyRing(props, env);
        minter = new TokenMinter(keys, props);

        // The verifying side, wired precisely as a deployed service would wire it: a Nimbus
        // decoder over this issuer's public key, feeding the untouched resolver with default
        // claim names (tenant_id / preferred_username / permissions / units).
        JwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keys.active().toRSAPublicKey()).build();
        AuthProperties authProps = new AuthProperties();
        authProps.setIssuerUri(ISSUER);
        resolver = new JwtIdentityResolver(decoder, authProps);
    }

    @Test
    @DisplayName("a staff token yields the same tenant, principal, permissions and units")
    void staffTokenParity() {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        String token = minter.staffToken(
                tenant,
                user,
                "ada.admin",
                List.of("customers:read", "products:create"),
                List.of("branch-01"),
                "fincore-web");

        IdentityContext ctx = resolver.resolve(bearer(token));

        assertThat(ctx.tenantId()).isEqualTo(tenant);
        assertThat(ctx.principal()).isEqualTo("user:ada.admin");
        assertThat(ctx.permissions()).containsExactlyInAnyOrder("customers:read", "products:create");
        assertThat(ctx.units()).containsExactly("branch-01");
        assertThat(ctx.tokenId()).isNotNull();
    }

    @Test
    @DisplayName("a service token carries azp and no tenant claim — the ledger's rule, unchanged")
    void serviceTokenHasNoTenant() {
        String token = minter.serviceToken("core");
        // libs/auth's resolver requires a tenant claim and must reject a tenantless token — which
        // is exactly why the ledger verifies service tokens under its own rules, not this resolver.
        assertThatThrownBy(() -> resolver.resolve(bearer(token)))
                .isInstanceOf(NotAuthenticatedException.class);
    }

    @Test
    @DisplayName("an action token is inert at any other service — no tenant, no permissions")
    void actionTokenIsInert() {
        String token = minter.actionToken(UUID.randomUUID(), UUID.randomUUID(), TokenMinter.ACTION_PASSWORD_CHANGE);
        assertThatThrownBy(() -> resolver.resolve(bearer(token)))
                .isInstanceOf(NotAuthenticatedException.class);
    }

    private static HttpServletRequest bearer(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
