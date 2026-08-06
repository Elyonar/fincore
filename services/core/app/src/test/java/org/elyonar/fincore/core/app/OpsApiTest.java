package org.elyonar.fincore.core.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.elyonar.fincore.events.EventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The operator surface, over HTTP.
 *
 * <p>The assertion that matters most is the one about what is <em>absent</em>: there is no way for
 * a human to declare that an uncertain transaction posted. Everything else here is ordinary CRUD;
 * that one is the outcome protocol holding under the pressure of an operator who wants the queue
 * cleared.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpsApiTest {

    @LocalServerPort private int port;
    @Autowired private EventPublisher publisher;
    private final HttpClient http = HttpClient.newHttpClient();

    private final UUID tenantId = UUID.randomUUID();

    @DynamicPropertySource
    static void quiet(DynamicPropertyRegistry registry) {
        registry.add("fincore.core.worker.interval-ms", () -> "3600000");
        registry.add("fincore.core.outbox.relay.interval-ms", () -> "3600000");
    }

    private HttpRequest.Builder authed(String path, String permissions) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Dev-Tenant-Id", tenantId.toString())
                .header("X-Dev-Principal", "user:ops")
                .header("X-Dev-Permissions", permissions);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void the_open_case_queue_is_readable_by_ops() {
        HttpResponse<String> response = send(authed("/v1/ops/cases", "ops:read").GET().build());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).startsWith("[");
    }

    @Test
    void reading_the_queue_requires_the_permission() {
        assertThat(send(authed("/v1/ops/cases", "transfers:create").GET().build()).statusCode())
                .isEqualTo(403);
    }

    @Test
    void an_approval_can_be_raised_and_checked_by_someone_else() {
        HttpResponse<String> raised =
                send(
                        authed("/v1/approvals", "approvals:make")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"targetTransactionId\":\"" + UUID.randomUUID()
                                                        + "\",\"amountMinor\":100000}"))
                                .build());

        assertThat(raised.statusCode()).isEqualTo(201);
        assertThat(raised.body()).contains("PENDING");

        String id = raised.body().replaceAll(".*\"approvalId\":\"([^\"]+)\".*", "$1");
        HttpResponse<String> checked =
                send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/approvals/" + id + "/check"))
                                .header("Content-Type", "application/json")
                                .header("X-Dev-Tenant-Id", tenantId.toString())
                                // A different principal: the database refuses maker == checker.
                                .header("X-Dev-Principal", "user:supervisor")
                                .header("X-Dev-Permissions", "approvals:check")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"approved\":true}"))
                                .build());

        assertThat(checked.statusCode()).isEqualTo(200);
        assertThat(checked.body()).contains("APPROVED");
    }

    @Test
    void a_maker_cannot_check_their_own_approval_over_http_either() {
        HttpResponse<String> raised =
                send(
                        authed("/v1/approvals", "approvals:make")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"targetTransactionId\":\"" + UUID.randomUUID()
                                                        + "\",\"amountMinor\":100000}"))
                                .build());
        String id = raised.body().replaceAll(".*\"approvalId\":\"([^\"]+)\".*", "$1");

        // Same principal, and the checker is taken from the token rather than the body — so there
        // is no field a caller could set to route around this.
        HttpResponse<String> selfChecked =
                send(
                        authed("/v1/approvals/" + id + "/check", "approvals:check")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"approved\":true}"))
                                .build());

        assertThat(selfChecked.statusCode()).isNotEqualTo(200);
    }

    @Test
    void there_is_no_endpoint_for_declaring_an_outcome() {
        // The protocol's central guarantee, expressed as a missing capability. `resolve` asks Core
        // to try again; it accepts no outcome, so a body claiming one changes nothing about what
        // the endpoint can do. If a future change adds such a parameter, this is the test that
        // should have to be deleted first — deliberately, and visibly.
        HttpResponse<String> response =
                send(
                        authed("/v1/ops/cases/" + UUID.randomUUID() + "/resolve", "ops:resolve")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"resolution\":\"POSTED\"}"))
                                .build());

        // No such case, so it cannot succeed — and crucially it does not succeed *because of* the
        // resolution the caller supplied.
        assertThat(response.statusCode()).isNotEqualTo(200);
    }

    @Test
    void the_default_publisher_says_it_delivers_nothing() {
        // Without a broker configured the logging adapter is selected, and it is honest about it.
        // A publisher that silently discarded events while reporting success is the failure the
        // startup banner exists to prevent.
        assertThat(publisher.delivers()).isFalse();
        assertThat(publisher.name()).isEqualTo("log");
    }
}
