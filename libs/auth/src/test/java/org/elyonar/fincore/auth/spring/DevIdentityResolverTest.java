package org.elyonar.fincore.auth.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.elyonar.fincore.auth.IdentityContext;
import org.elyonar.fincore.auth.NotAuthenticatedException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The guard that keeps an unverified resolver out of a deployed environment.
 *
 * <p>ADR 0010 permits this resolver to stand in only while it is impossible to enable accidentally.
 * "Impossible to enable accidentally" is a claim, and this is the test that makes it one.
 */
class DevIdentityResolverTest {

    private static final String[] SANCTIONED = {"dev", "test", "local"};

    @Test
    void refuses_to_start_when_no_sanctioned_profile_is_active() {
        assertThatThrownBy(() -> new DevIdentityResolver(new String[] {"prod"}, SANCTIONED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("verifies nothing")
                .hasMessageContaining("fincore.auth.mode=jwt");
    }

    @Test
    void refuses_to_start_when_no_profile_is_active_at_all() {
        // The default. Setting the mode alone must not be enough — that is the single stray
        // environment variable this guard exists to survive.
        assertThatThrownBy(() -> new DevIdentityResolver(new String[] {}, SANCTIONED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void starts_under_a_sanctioned_profile() {
        assertThat(new DevIdentityResolver(new String[] {"dev"}, SANCTIONED).verifies()).isFalse();
    }

    @Test
    void reports_that_it_verifies_nothing() {
        DevIdentityResolver resolver = new DevIdentityResolver(new String[] {"test"}, SANCTIONED);
        assertThat(resolver.verifies()).isFalse();
        assertThat(resolver.name()).isEqualTo("dev");
    }

    @Test
    void reads_identity_from_headers_in_dev() {
        DevIdentityResolver resolver = new DevIdentityResolver(new String[] {"test"}, SANCTIONED);
        UUID tenant = UUID.randomUUID();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(DevIdentityResolver.TENANT_HEADER, tenant.toString());
        request.addHeader(DevIdentityResolver.PRINCIPAL_HEADER, "user:ada");
        request.addHeader(DevIdentityResolver.PERMISSIONS_HEADER, "transfers:create, transfers:read");
        request.addHeader(DevIdentityResolver.SERVICE_HEADER, "core-orchestration");

        IdentityContext context = resolver.resolve(request);

        assertThat(context.tenantId()).isEqualTo(tenant);
        assertThat(context.principal()).isEqualTo("user:ada");
        assertThat(context.permissions()).containsExactlyInAnyOrder("transfers:create", "transfers:read");
        assertThat(context.calledBy("core-orchestration")).isTrue();
    }

    @Test
    void a_malformed_tenant_is_rejected_rather_than_coerced() {
        DevIdentityResolver resolver = new DevIdentityResolver(new String[] {"test"}, SANCTIONED);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(DevIdentityResolver.TENANT_HEADER, "not-a-uuid");
        request.addHeader(DevIdentityResolver.PRINCIPAL_HEADER, "user:ada");

        assertThatThrownBy(() -> resolver.resolve(request))
                .isInstanceOf(NotAuthenticatedException.class);
    }

    @Test
    void absent_permissions_grant_nothing() {
        DevIdentityResolver resolver = new DevIdentityResolver(new String[] {"test"}, SANCTIONED);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(DevIdentityResolver.TENANT_HEADER, UUID.randomUUID().toString());
        request.addHeader(DevIdentityResolver.PRINCIPAL_HEADER, "user:ada");

        assertThat(resolver.resolve(request).permissions()).isEmpty();
    }
}
