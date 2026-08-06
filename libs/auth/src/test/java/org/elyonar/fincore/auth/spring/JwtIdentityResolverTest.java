package org.elyonar.fincore.auth.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.elyonar.fincore.auth.IdentityContext;
import org.elyonar.fincore.auth.NotAuthenticatedException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * The production identity path.
 *
 * <p>The decoder is stubbed because signature verification is Nimbus's job and is proven by its own
 * suite; what is under test here is everything this library decides <em>after</em> a token is
 * accepted — most importantly that the tenant comes from a claim and can never be supplied by the
 * caller.
 */
class JwtIdentityResolverTest {

    private static final UUID TENANT = UUID.randomUUID();

    private static AuthProperties properties() {
        return new AuthProperties();
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return new Jwt(
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(600),
                Map.of("alg", "RS256"),
                claims);
    }

    private static MockHttpServletRequest bearer() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token-value");
        return request;
    }

    private static JwtIdentityResolver resolverFor(Jwt token) {
        JwtDecoder decoder = value -> token;
        return new JwtIdentityResolver(decoder, properties());
    }

    @Test
    void extracts_tenant_principal_and_permissions_from_claims() {
        JwtIdentityResolver resolver =
                resolverFor(
                        jwt(
                                Map.of(
                                        "sub", "abc-123",
                                        "tenant_id", TENANT.toString(),
                                        "preferred_username", "ada.o@branch-01",
                                        "permissions", List.of("transfers:create", "transfers:read"),
                                        "jti", "token-id-1")));

        IdentityContext context = resolver.resolve(bearer());

        assertThat(context.tenantId()).isEqualTo(TENANT);
        assertThat(context.principal()).isEqualTo("user:ada.o@branch-01");
        assertThat(context.permissions())
                .containsExactlyInAnyOrder("transfers:create", "transfers:read");
        assertThat(context.tokenId()).isEqualTo("token-id-1");
    }

    @Test
    void a_header_can_never_supply_the_tenant() {
        // The point of ADR 0009. A caller that both presents a valid token and asserts a different
        // tenant in a header gets the token's tenant, always.
        UUID claimed = UUID.randomUUID();
        JwtIdentityResolver resolver =
                resolverFor(
                        jwt(Map.of("sub", "abc", "tenant_id", TENANT.toString())));

        MockHttpServletRequest request = bearer();
        request.addHeader("X-Tenant-Id", claimed.toString());
        request.addHeader("X-Dev-Tenant-Id", claimed.toString());

        assertThat(resolver.resolve(request).tenantId()).isEqualTo(TENANT);
    }

    @Test
    void a_token_without_a_tenant_claim_is_refused() {
        JwtIdentityResolver resolver = resolverFor(jwt(Map.of("sub", "abc")));

        assertThatThrownBy(() -> resolver.resolve(bearer()))
                .isInstanceOf(NotAuthenticatedException.class)
                .hasMessageContaining("tenant_id");
    }

    @Test
    void a_non_uuid_tenant_claim_is_refused_rather_than_coerced() {
        JwtIdentityResolver resolver =
                resolverFor(jwt(Map.of("sub", "abc", "tenant_id", "acme-bank")));

        assertThatThrownBy(() -> resolver.resolve(bearer()))
                .isInstanceOf(NotAuthenticatedException.class);
    }

    @Test
    void the_subject_stands_in_when_the_username_claim_is_absent() {
        JwtIdentityResolver resolver =
                resolverFor(jwt(Map.of("sub", "abc-123", "tenant_id", TENANT.toString())));

        assertThat(resolver.resolve(bearer()).principal()).isEqualTo("user:abc-123");
    }

    @Test
    void a_token_with_no_permissions_claim_may_do_nothing() {
        JwtIdentityResolver resolver =
                resolverFor(jwt(Map.of("sub", "abc", "tenant_id", TENANT.toString())));

        assertThat(resolver.resolve(bearer()).permissions()).isEmpty();
    }

    @Test
    void a_request_without_a_bearer_token_is_refused() {
        JwtIdentityResolver resolver =
                resolverFor(jwt(Map.of("sub", "abc", "tenant_id", TENANT.toString())));

        assertThatThrownBy(() -> resolver.resolve(new MockHttpServletRequest()))
                .isInstanceOf(NotAuthenticatedException.class);

        MockHttpServletRequest wrongScheme = new MockHttpServletRequest();
        wrongScheme.addHeader("Authorization", "Basic YWRhOnNlY3JldA==");
        assertThatThrownBy(() -> resolver.resolve(wrongScheme))
                .isInstanceOf(NotAuthenticatedException.class);
    }

    @Test
    void a_rejected_token_does_not_leak_why() {
        JwtDecoder failing =
                value -> {
                    throw new BadJwtException("signature mismatch for key kid-7");
                };
        JwtIdentityResolver resolver = new JwtIdentityResolver(failing, properties());

        assertThatThrownBy(() -> resolver.resolve(bearer()))
                .isInstanceOf(NotAuthenticatedException.class)
                .hasMessage("token rejected")
                .hasMessageNotContaining("kid-7");
    }

    @Test
    void the_service_identity_is_absent_without_a_verified_client_certificate() {
        // It comes from the TLS peer certificate, never from a header a caller could set — so a
        // plain request is on nobody's allowlist.
        JwtIdentityResolver resolver =
                resolverFor(jwt(Map.of("sub", "abc", "tenant_id", TENANT.toString())));

        MockHttpServletRequest request = bearer();
        request.addHeader("X-Service-Identity", "core-orchestration");

        assertThat(resolver.resolve(request).serviceIdentity()).isNull();
        assertThat(resolver.resolve(request).calledBy("core-orchestration")).isFalse();
    }

    @Test
    void the_service_identity_reads_the_common_name_of_a_verified_certificate() {
        JwtIdentityResolver resolver =
                resolverFor(jwt(Map.of("sub", "abc", "tenant_id", TENANT.toString())));

        MockHttpServletRequest request = bearer();
        request.setAttribute(
                "jakarta.servlet.request.X509Certificate",
                new java.security.cert.X509Certificate[] {
                    TestCertificates.withSubject("CN=core-orchestration,OU=fincore")
                });

        assertThat(resolver.resolve(request).calledBy("core-orchestration")).isTrue();
    }
}
