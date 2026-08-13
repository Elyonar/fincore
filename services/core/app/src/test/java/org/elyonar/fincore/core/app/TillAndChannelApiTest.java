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
import tools.jackson.databind.json.JsonMapper;

/**
 * Two authorization inputs that used to be caller assertions, now gated.
 *
 * <p>A till names a branch, and the Organization module is the authority on branches — so
 * provisioning one against a code nothing owns is refused rather than transcribed. And the
 * channel a transfer names selects its limit rules, so asserting one costs a permission
 * ({@code channel:api} / {@code channel:teller}); a channel the platform does not model is
 * malformed, and a channel the token does not license is a 403 (ADR 0012).
 *
 * <p>The refusals here are all Phase A refusals: no stub ledger is running, which is itself part
 * of the assertion — none of these requests may reach Phase B.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TillAndChannelApiTest {

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

    private HttpRequest.Builder authed(String path, String permissions) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Dev-Tenant-Id", tenantId.toString())
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

    private void createBranch(String code, String type) {
        HttpResponse<String> created =
                send(authed("/v1/org-units", "org:manage")
                                .POST(HttpRequest.BodyPublishers.ofString(
                                        "{\"code\":\"%s\",\"name\":\"%s\",\"unitType\":\"%s\"}"
                                                .formatted(code, code, type)))
                                .build());
        assertThat(created.statusCode()).isEqualTo(201);
    }

    private HttpResponse<String> openTill(String branchCode) {
        return send(authed("/v1/tills", "tills:manage")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"branchCode\":\"%s\",\"ledgerAccountId\":\"%s\",\"currency\":\"NGN\",\"assignedTo\":\"user:ada\"}"
                                        .formatted(branchCode, UUID.randomUUID())))
                        .build());
    }

    @Test
    void a_till_opens_only_inside_an_existing_branch() throws Exception {
        HttpResponse<String> refused = openTill("branch-nowhere");
        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(refused.body()).contains("UNIT_NOT_FOUND");

        createBranch("branch-10", "BRANCH");
        HttpResponse<String> opened = openTill("branch-10");
        assertThat(opened.statusCode()).isEqualTo(201);
        String tillId = mapper.readTree(opened.body()).get("tillId").asString();

        HttpResponse<String> list = send(authed("/v1/tills", "tills:read").GET().build());
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains(tillId).contains("branch-10");

        assertThat(send(authed("/v1/tills/" + tillId + "/close", "tills:manage")
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build())
                        .statusCode())
                .isEqualTo(200);
    }

    /**
     * A till can change hands after it is opened.
     *
     * <p>It could not, until now. {@code assigned_to} was writable only in the INSERT, so a drawer
     * opened before anybody knew whose it would be — which is the ordinary case during setup, and
     * exactly what the setup flow's "nobody yet" option produces — stayed unassigned for its whole
     * life. The only remedy was to close it and open another, which is a different till against the
     * same physical drawer.
     *
     * <p>The closed case is the point of the second half. Reassigning a closed till would rewrite
     * who was answerable for cash already counted and signed off.
     */
    @Test
    void an_open_till_can_change_hands_and_a_closed_one_cannot() throws Exception {
        createBranch("branch-20", "BRANCH");
        String tillId = mapper.readTree(openTill("branch-20").body()).get("tillId").asString();

        HttpResponse<String> reassigned = assign(tillId, "\"user:ngozi.teller\"", "tills:manage");
        assertThat(reassigned.statusCode()).isEqualTo(200);
        assertThat(send(authed("/v1/tills", "tills:read").GET().build()).body())
                .contains("user:ngozi.teller");

        // Nobody is a state, not an omission: a teller leaves and the drawer stays.
        assertThat(assign(tillId, "null", "tills:manage").statusCode()).isEqualTo(200);
        assertThat(send(authed("/v1/tills", "tills:read").GET().build()).body())
                .doesNotContain("user:ngozi.teller");

        // Reading a till does not license handing it to somebody.
        assertThat(assign(tillId, "\"user:ada\"", "tills:read").statusCode()).isEqualTo(403);

        send(authed("/v1/tills/" + tillId + "/close", "tills:manage")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
        HttpResponse<String> tooLate = assign(tillId, "\"user:ada\"", "tills:manage");
        assertThat(tooLate.statusCode()).isEqualTo(422);
        assertThat(tooLate.body()).contains("TILL_NOT_OPEN");

        // An id nothing owns is the same refusal, so a probe learns nothing from the difference.
        assertThat(assign(UUID.randomUUID().toString(), "\"user:ada\"", "tills:manage").statusCode())
                .isEqualTo(422);
    }

    private HttpResponse<String> assign(String tillId, String assignedTo, String permissions) {
        return send(authed("/v1/tills/" + tillId + "/assignment", permissions)
                        .PUT(HttpRequest.BodyPublishers.ofString(
                                "{\"assignedTo\":%s}".formatted(assignedTo)))
                        .build());
    }

    @Test
    void a_unit_that_is_not_a_branch_cannot_take_a_till() {
        createBranch("ops-team", "OPERATIONS_TEAM");
        HttpResponse<String> refused = openTill("ops-team");
        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(refused.body()).contains("UNIT_NOT_FOUND");
    }

    @Test
    void till_administration_denies_by_default() {
        assertThat(openTillWith("transfers:create").statusCode()).isEqualTo(403);
        assertThat(send(authed("/v1/tills", "tills:manage").GET().build()).statusCode()).isEqualTo(403);
    }

    private HttpResponse<String> openTillWith(String permissions) {
        return send(authed("/v1/tills", permissions)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"branchCode\":\"b\",\"ledgerAccountId\":\"%s\",\"currency\":\"NGN\"}"
                                        .formatted(UUID.randomUUID())))
                        .build());
    }

    private HttpResponse<String> transfer(String permissions, String channelField) {
        String body =
                """
                {"idempotencyKey":"%s","customerId":"%s","fromAccountId":"%s","toAccountId":"%s",
                 "amountMinor":1000,"currency":"NGN","productCode":"P"%s}
                """
                        .formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), channelField);
        return send(authed("/v1/transfers", permissions)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build());
    }

    @Test
    void asserting_a_channel_costs_the_matching_permission() {
        // The default channel is API; without channel:api the request stops at the gate.
        assertThat(transfer("transfers:create", "").statusCode()).isEqualTo(403);
        // Naming TELLER with only the API channel licence is refused the same way.
        assertThat(transfer("transfers:create,channel:api", ",\"channel\":\"TELLER\"").statusCode())
                .isEqualTo(403);
    }

    @Test
    void a_channel_the_platform_does_not_model_is_malformed_not_licensable() {
        HttpResponse<String> response = transfer("transfers:create,channel:api", ",\"channel\":\"CARRIER_PIGEON\"");
        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("COMMAND_INVALID").contains("CHANNEL_INVALID");
    }
}
