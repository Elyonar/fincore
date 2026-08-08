package org.elyonar.fincore.core.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The organizational surface (ADR 0012), over HTTP.
 *
 * <p>Two properties carry the design. Codes never recycle — a closed branch's code stays taken,
 * because an old till answering to a reused code would make its audit trail ambiguous. And
 * everything is tenant-scoped the way every other module is: another tenant's tree is not merely
 * forbidden, it is invisible.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrgUnitApiTest {

    @Autowired private TenantRegistry tenantRegistry;

    @LocalServerPort private int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper mapper = JsonMapper.builder().build();

    private UUID tenantId;

    @BeforeEach
    void registerTheTenant() {
        tenantId = UUID.randomUUID();
        tenantRegistry.register(tenantId, "test tenant", "test");
    }

    @DynamicPropertySource
    static void quiet(DynamicPropertyRegistry registry) {
        registry.add("fincore.core.worker.interval-ms", () -> "3600000");
        registry.add("fincore.core.outbox.relay.interval-ms", () -> "3600000");
    }

    private HttpRequest.Builder authed(UUID tenant, String path, String permissions) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Dev-Tenant-Id", tenant.toString())
                .header("X-Dev-Principal", "user:grace")
                .header("X-Dev-Permissions", permissions);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpResponse<String> create(UUID tenant, String code, String type, String parent) {
        String body =
                "{\"code\":\"%s\",\"name\":\"%s\",\"unitType\":\"%s\"%s}"
                        .formatted(code, code, type, parent == null ? "" : ",\"parentCode\":\"" + parent + "\"");
        return send(
                authed(tenant, "/v1/org-units", "org:manage")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build());
    }

    @Test
    void a_unit_is_created_read_and_listed() throws Exception {
        HttpResponse<String> created = create(tenantId, "branch-01", "BRANCH", null);
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode unit = mapper.readTree(created.body());
        assertThat(unit.get("code").asString()).isEqualTo("branch-01");
        assertThat(unit.get("status").asString()).isEqualTo("ACTIVE");

        HttpResponse<String> read =
                send(authed(tenantId, "/v1/org-units/" + unit.get("id").asString(), "org:read").GET().build());
        assertThat(read.statusCode()).isEqualTo(200);

        HttpResponse<String> list = send(authed(tenantId, "/v1/org-units", "org:read").GET().build());
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("branch-01");
    }

    @Test
    void the_tree_hangs_under_named_parents_and_a_missing_parent_is_refused() {
        assertThat(create(tenantId, "region-north", "REGION", null).statusCode()).isEqualTo(201);
        assertThat(create(tenantId, "branch-02", "BRANCH", "region-north").statusCode()).isEqualTo(201);

        HttpResponse<String> orphan = create(tenantId, "branch-03", "BRANCH", "region-ghost");
        assertThat(orphan.statusCode()).isEqualTo(422);
        assertThat(orphan.body()).contains("PARENT_UNIT_NOT_FOUND");
    }

    @Test
    void codes_never_recycle_even_after_a_close() throws Exception {
        JsonNode unit = mapper.readTree(create(tenantId, "branch-04", "BRANCH", null).body());
        assertThat(
                        send(authed(tenantId, "/v1/org-units/" + unit.get("id").asString() + "/close", "org:manage")
                                        .POST(HttpRequest.BodyPublishers.noBody())
                                        .build())
                                .statusCode())
                .isEqualTo(200);

        HttpResponse<String> reused = create(tenantId, "branch-04", "BRANCH", null);
        assertThat(reused.statusCode()).isEqualTo(409);
        assertThat(reused.body()).contains("UNIT_CODE_TAKEN");
    }

    @Test
    void assignments_are_attributed_single_per_principal_and_revocable() throws Exception {
        JsonNode unit = mapper.readTree(create(tenantId, "branch-05", "BRANCH", null).body());
        String base = "/v1/org-units/" + unit.get("id").asString() + "/assignments";
        String ada = "{\"principal\":\"user:ada.o\"}";

        assertThat(send(authed(tenantId, base, "org:manage").POST(HttpRequest.BodyPublishers.ofString(ada)).build())
                        .statusCode())
                .isEqualTo(201);
        // The live-assignment index arbitrates, not application code.
        HttpResponse<String> duplicate =
                send(authed(tenantId, base, "org:manage").POST(HttpRequest.BodyPublishers.ofString(ada)).build());
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(duplicate.body()).contains("ASSIGNMENT_EXISTS");

        assertThat(send(authed(tenantId, base, "org:read").GET().build()).body()).contains("user:ada.o");

        assertThat(send(authed(tenantId, base + "/revoke", "org:manage")
                                .POST(HttpRequest.BodyPublishers.ofString(ada))
                                .build())
                        .statusCode())
                .isEqualTo(200);
        // Revoking what is not assigned is a 404, not a no-op — silence here would hide a typo.
        HttpResponse<String> again =
                send(authed(tenantId, base + "/revoke", "org:manage")
                                .POST(HttpRequest.BodyPublishers.ofString(ada))
                                .build());
        assertThat(again.statusCode()).isEqualTo(404);
        assertThat(again.body()).contains("ASSIGNMENT_NOT_FOUND");
    }

    @Test
    void the_surface_denies_by_default() {
        assertThat(send(authed(tenantId, "/v1/org-units", "transfers:create").GET().build()).statusCode())
                .isEqualTo(403);
        assertThat(create(tenantId, "x", "BRANCH", null).statusCode()).isEqualTo(201);
        assertThat(
                        send(authed(tenantId, "/v1/org-units", "org:read")
                                        .POST(HttpRequest.BodyPublishers.ofString("{\"code\":\"y\",\"name\":\"y\",\"unitType\":\"BRANCH\"}"))
                                        .build())
                                .statusCode())
                .isEqualTo(403);
    }

    @Test
    void another_tenants_tree_is_invisible_not_forbidden() throws Exception {
        JsonNode unit = mapper.readTree(create(tenantId, "branch-06", "BRANCH", null).body());

        UUID otherTenant = UUID.randomUUID();
        tenantRegistry.register(otherTenant, "other tenant", "test");
        HttpResponse<String> foreign =
                send(authed(otherTenant, "/v1/org-units/" + unit.get("id").asString(), "org:read").GET().build());
        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(send(authed(otherTenant, "/v1/org-units", "org:read").GET().build()).body())
                .doesNotContain("branch-06");
    }
}
