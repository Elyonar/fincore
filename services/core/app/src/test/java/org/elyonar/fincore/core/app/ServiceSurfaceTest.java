package org.elyonar.fincore.core.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The surface an operator or an integrator meets first.
 *
 * <p>These are unauthenticated on purpose, and that is exactly why they are tested: an open path is
 * a deliberate hole in deny-by-default, so it should be a hole someone chose rather than one that
 * appeared. A readiness probe that answers 401 means an orchestrator never sees the service as up,
 * and the first symptom is a deploy that never goes live.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServiceSurfaceTest {

    @LocalServerPort private int port;
    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path) {
        try {
            return http.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void the_root_answers_rather_than_404ing() {
        HttpResponse<String> response = get("/");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("fincore-core").contains("/docs");
    }

    @Test
    void health_and_readiness_answer_without_a_caller() {
        assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(200);
    }

    @Test
    void the_generated_openapi_document_is_reachable_and_describes_the_money_path() {
        HttpResponse<String> spec = get("/v3/api-docs");

        assertThat(spec.statusCode()).isEqualTo(200);
        // Generated from the code, so it cannot describe an endpoint the service does not serve.
        assertThat(spec.body()).contains("/v1/transfers").contains("/v1/transactions/{id}");
    }

    @Test
    void swagger_is_served_at_docs() {
        // A redirect to the UI's index counts: the point is that /docs resolves rather than 404s.
        assertThat(get("/docs").statusCode()).isIn(200, 302);
    }

    @Test
    void the_money_path_is_still_closed() {
        // The open paths above must not have opened anything else. Deny by default still holds.
        assertThat(get("/v1/transactions/" + java.util.UUID.randomUUID()).statusCode()).isEqualTo(401);
    }
}
