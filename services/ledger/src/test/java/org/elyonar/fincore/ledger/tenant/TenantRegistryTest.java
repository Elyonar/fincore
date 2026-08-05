package org.elyonar.fincore.ledger.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.elyonar.fincore.ledger.api.TenantResolver;
import org.elyonar.fincore.ledger.shared.LedgerException;
import org.elyonar.fincore.ledger.support.LedgerHttpTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A well-formed UUID is not a tenant.
 *
 * <p>Before the registry existed, any id in the header produced a working, empty ledger with
 * platform defaults. Every isolation test passed, because isolation between tenants was never what
 * was broken — the question of whether a tenant was real had simply never been asked.
 */
@DisplayName("tenant registry — a tenant must be provisioned before it can hold money")
class TenantRegistryTest extends LedgerHttpTest {

    @Autowired TenantRegistry registry;

    @BeforeEach
    void seed() {
        seedTenant();
    }

    @Test
    @DisplayName("an unprovisioned tenant is refused, however well-formed its id")
    void unknown_tenant_is_refused() {
        assertThatThrownBy(() -> registry.requireActive(UUID.randomUUID()))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("unknown tenant");
    }

    @Test
    @DisplayName("a suspended tenant is refused too")
    void suspended_tenant_is_refused() {
        UUID suspended = UUID.randomUUID();
        registry.register(suspended, "suspended co", "test");
        jdbc.update("UPDATE tenants SET status='SUSPENDED' WHERE id = ?", suspended);

        assertThatThrownBy(() -> registry.requireActive(suspended)).isInstanceOf(LedgerException.class);
    }

    @Test
    @DisplayName("a provisioned tenant passes")
    void registered_tenant_is_accepted() {
        registry.requireActive(tenant);
    }

    @Test
    @DisplayName("over HTTP, an unknown tenant is 404 — not an empty ledger of its own")
    void unknown_tenant_over_http_is_404() throws Exception {
        mvc.perform(
                        get("/v1/periods")
                                .header(TenantResolver.TENANT_HEADER, UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the registry does not reveal which tenants exist")
    void unknown_tenant_is_not_an_enumeration_oracle() throws Exception {
        // Same status and same body as any other not-found: a caller cannot probe for the
        // existence of tenants it has no business knowing about.
        var unknownTenant =
                mvc.perform(get("/v1/periods").header(TenantResolver.TENANT_HEADER, UUID.randomUUID().toString()))
                        .andReturn();
        var unknownAccount =
                mvc.perform(get("/v1/accounts/" + UUID.randomUUID()).header(TenantResolver.TENANT_HEADER, tenant.toString()))
                        .andReturn();

        assertThat(unknownTenant.getResponse().getStatus())
                .isEqualTo(unknownAccount.getResponse().getStatus());
    }
}
