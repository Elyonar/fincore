package org.elyonar.fincore.core.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A tenant Core has never heard of is refused.
 *
 * <p>Row-level security isolates tenants from one another and says nothing about whether a tenant
 * is real. Before this gate, any UUID in a validated token got its own empty, functioning slice of
 * Core — and every isolation test passed throughout, because isolation was never what was broken.
 * The ledger reached the same conclusion in its V6; this is the second deployable to need it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("tenant gate — a tenant must exist before Core will serve it")
class TenantGateTest {

    @LocalServerPort private int port;
    @Autowired private TenantRegistry tenantRegistry;
    @Autowired @Qualifier("ownerDataSource") private javax.sql.DataSource owner;

    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void quiet(DynamicPropertyRegistry registry) {
        registry.add("fincore.core.worker.interval-ms", () -> "3600000");
        registry.add("fincore.core.outbox.relay.interval-ms", () -> "3600000");
    }

    @Test
    @DisplayName("an unregistered tenant is refused, whatever its permissions say")
    void an_unknown_tenant_cannot_reach_anything() {
        UUID neverProvisioned = UUID.randomUUID();

        // A perfectly valid identity: the right permission, a well-formed tenant. The only thing
        // wrong with it is that nobody ever provisioned that tenant — which used to be enough.
        assertThat(get("/v1/products", neverProvisioned, "products:read")).isEqualTo(404);
    }

    @Test
    @DisplayName("404, not 403 — which tenants exist is not something to confirm")
    void the_refusal_does_not_confirm_what_exists() {
        UUID neverProvisioned = UUID.randomUUID();

        // A 403 would say "that tenant is real, you just may not touch it", which is an
        // enumeration oracle. The same choice the ledger made, and the same one Core already makes
        // for a customer belonging to someone else.
        assertThat(get("/v1/products", neverProvisioned, "products:read")).isEqualTo(404);
        assertThat(get("/v1/products", neverProvisioned, "nothing:useful")).isEqualTo(404);
    }

    @Test
    @DisplayName("a registered tenant passes, and then the permission decides")
    void a_registered_tenant_is_served() {
        UUID provisioned = UUID.randomUUID();
        tenantRegistry.register(provisioned, "a real bank", "TenantGateTest");

        assertThat(get("/v1/products", provisioned, "products:read")).isEqualTo(200);
        // The gate is not authorization. Once the tenant is real, the permission still decides.
        assertThat(get("/v1/products", provisioned, "wrong:permission")).isEqualTo(403);
    }

    @Test
    @DisplayName("a suspended tenant is refused as firmly as an unknown one")
    void suspending_a_tenant_closes_it() {
        UUID provisioned = UUID.randomUUID();
        tenantRegistry.register(provisioned, "a bank in arrears", "TenantGateTest");
        assertThat(get("/v1/products", provisioned, "products:read")).isEqualTo(200);

        new JdbcTemplate(owner)
                .update("UPDATE platform.tenants SET status = 'SUSPENDED' WHERE id = ?", provisioned);

        // Entitlements are a control plane concern and this is the seam they will act through:
        // suspension has to stop traffic, not merely record an intention.
        assertThat(get("/v1/products", provisioned, "products:read")).isEqualTo(404);
    }

    @Test
    @DisplayName("open paths stay open — they have no tenant to check")
    void the_gate_does_not_close_the_front_door() {
        // An orchestrator's probe holds no token and therefore no tenant. A gate that demanded one
        // would make the service unschedulable, which is a worse failure than the one it prevents.
        assertThat(anonymous("/actuator/health/readiness")).isEqualTo(200);
        assertThat(anonymous("/")).isEqualTo(200);
    }

    private int get(String path, UUID tenantId, String permissions) {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Dev-Tenant-Id", tenantId.toString())
                .header("X-Dev-Principal", "user:test")
                .header("X-Dev-Permissions", permissions)
                .GET()
                .build());
    }

    private int anonymous(String path) {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET()
                .build());
    }

    private int send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
