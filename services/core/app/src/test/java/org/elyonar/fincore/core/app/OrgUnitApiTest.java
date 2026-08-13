package org.elyonar.fincore.core.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.core.organization.api.UnitClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
@Import(OrgUnitApiTest.RecordingClaims.class)
class OrgUnitApiTest {

    @Autowired private TenantRegistry tenantRegistry;
    @Autowired private RecordedUnitClaims claims;

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

    /**
     * A unit code reaches token consumers verbatim through the {@code units} claim and is never
     * renamed, so a malformed one is permanent. The schema only bounds length, which admitted
     * codes carrying spaces and punctuation until the record layer began refusing them.
     */
    @Test
    void a_malformed_code_is_refused_rather_than_stored() throws Exception {
        for (String malformed : new String[] {"HEAD OFFICE 2!!", "Branch_01", "-branch", "branch-", "br--anch"}) {
            HttpResponse<String> refused = create(tenantId, malformed, "BRANCH", null);
            assertThat(refused.statusCode()).as("code %s", malformed).isEqualTo(422);
            assertThat(refused.body()).contains("UNIT_CODE_INVALID");
        }

        assertThat(create(tenantId, "branch-2b", "BRANCH", null).statusCode()).isEqualTo(201);
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

    /**
     * The claim half of an assignment (ADR 0012).
     *
     * <p>An assignment row is only half the fact. Scope is enforced from the token's {@code units}
     * claim, so a screen that writes the row and stops leaves a teller recorded in a branch and
     * still carrying yesterday's scope in every token they mint. This asserts the derivation
     * happens on both writes and that it is read back from the rows rather than echoed from the
     * request — provisioning follows the system of record, it does not run alongside it.
     */
    @Test
    void assigning_and_revoking_both_re_derive_the_units_claim() throws Exception {
        JsonNode first = mapper.readTree(create(tenantId, "branch-06", "BRANCH", null).body());
        JsonNode second = mapper.readTree(create(tenantId, "branch-07", "BRANCH", null).body());
        String ada = "{\"principal\":\"user:ada.o\"}";
        claims.seen.clear();

        assign(first, ada);
        assertThat(claims.seen).containsExactly(List.of("branch-06"));

        // The second assignment carries both, because the claim is the whole of what the rows say
        // and not the one unit this request happened to name.
        assign(second, ada);
        assertThat(claims.seen).last().isEqualTo(List.of("branch-06", "branch-07"));

        send(authed(tenantId, "/v1/org-units/" + first.get("id").asString() + "/assignments/revoke", "org:manage")
                .POST(HttpRequest.BodyPublishers.ofString(ada))
                .build());
        assertThat(claims.seen).last().isEqualTo(List.of("branch-07"));

        // A refused write must not move the claim: the row is the one that can refuse, and it did.
        int before = claims.seen.size();
        assign(second, ada);
        assertThat(claims.seen).hasSize(before);
    }

    private void assign(JsonNode unit, String principal) {
        send(authed(tenantId, "/v1/org-units/" + unit.get("id").asString() + "/assignments", "org:manage")
                .POST(HttpRequest.BodyPublishers.ofString(principal))
                .build());
    }

    /**
     * Stands in for the directory, recording what it was asked to mint.
     *
     * <p>A recording bean rather than a mocking framework, because the assertion is about a
     * sequence of values and reads better as one.
     */
    @TestConfiguration
    static class RecordingClaims {
        @Bean
        @Primary
        RecordedUnitClaims recordedUnitClaims() {
            return new RecordedUnitClaims();
        }
    }

    static class RecordedUnitClaims implements UnitClaims {
        final List<List<String>> seen = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void refresh(UUID tenantId, String principal, List<String> unitCodes) {
            seen.add(List.copyOf(unitCodes));
        }
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
