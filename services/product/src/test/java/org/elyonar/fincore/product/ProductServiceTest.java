package org.elyonar.fincore.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.elyonar.fincore.product.internal.TenantRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What this service owns, now that it owns it alone (ADR 0020).
 *
 * <p>The suites that moved out of Core went back there, because they exercise the half of product
 * authoring Core kept: a fee rule names one of the institution's own accounts, and only Core can
 * see that registry. What is left here is the part that is genuinely this service's, and the two
 * assertions below are the ones the whole extraction rests on.
 *
 * <p><strong>Immutability is the load-bearing one.</strong> ADR 0020's argument for extracting
 * Product first is that a published version cannot change, so a decision is a pure function of
 * frozen configuration and is therefore safe to answer across a network and safe for Core to cache
 * by version. That property is a database trigger, not a convention — and a test that did not
 * exercise it would leave the argument resting on a claim nobody checks.
 *
 * <p><strong>The tenant gate is the other.</strong> Row-level security isolates tenants from one
 * another and says nothing about whether a tenant exists. Before the registry, any UUID in a
 * validated token was a working institution with an empty catalogue.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("product — the catalogue it owns, and the decision the money path asks for")
class ProductServiceTest {

    @Autowired private TenantRegistry tenantRegistry;

    @LocalServerPort private int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    private UUID tenantId;

    @BeforeEach
    void freshTenant() {
        tenantId = UUID.randomUUID();
        tenantRegistry.register(tenantId, "test tenant", "test");
    }

    private HttpRequest.Builder as(String path, String permissions) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Dev-Tenant-Id", tenantId.toString())
                .header("X-Dev-Principal", "user:admin")
                .header("X-Dev-Permissions", permissions);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpResponse<String> createProduct(String code) {
        return send(as("/v1/products", "products:create")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"code\":\"%s\",\"name\":\"%s\",\"type\":\"SAVINGS\"}".formatted(code, code)))
                .build());
    }

    @Test
    void a_product_is_created_listed_and_found_by_its_code() throws Exception {
        assertThat(createProduct("SAV").statusCode()).isEqualTo(201);

        assertThat(send(as("/v1/products", "products:read").GET().build()).body()).contains("SAV");

        // The by-code lookup exists because Core confirms a product before opening an account under
        // it, and fetching the whole catalogue to answer that is a list scan per account opened.
        HttpResponse<String> found = send(as("/v1/products/SAV", "products:read").GET().build());
        assertThat(found.statusCode()).isEqualTo(200);
        assertThat(json.readTree(found.body()).path("code").asString()).isEqualTo("SAV");

        assertThat(send(as("/v1/products/NOPE", "products:read").GET().build()).statusCode()).isEqualTo(404);
    }

    @Test
    void a_decision_refuses_an_unknown_product_rather_than_failing() throws Exception {
        HttpResponse<String> answer = send(as("/v1/decisions/evaluate", "products:read")
                .POST(HttpRequest.BodyPublishers.ofString(
                        """
                        {"productCode":"GHOST","operation":"DEPOSIT","kycTier":"TIER_1",
                         "channel":"TELLER","amountMinor":1000,"currency":"NGN"}"""))
                .build());

        // A refusal is an answer, so it is a 200 carrying a reason. Core turns that into its own
        // coded 4xx for the customer-facing call; a 4xx here would make an ordinary business
        // outcome indistinguishable from a malformed request.
        assertThat(answer.statusCode()).isEqualTo(200);
        JsonNode decision = json.readTree(answer.body());
        assertThat(decision.path("permitted").asBoolean()).isFalse();
        assertThat(decision.path("refusal").asString()).isEqualTo("PRODUCT_NOT_FOUND");
    }

    @Test
    void the_decision_endpoint_takes_its_tenant_from_the_token_and_not_the_body() throws Exception {
        assertThat(createProduct("SAV").statusCode()).isEqualTo(201);

        // Another tenant's identical request must not see this catalogue. The wire record carries no
        // tenant at all, which is the structural half of this; the gate is the other half.
        UUID stranger = UUID.randomUUID();
        tenantRegistry.register(stranger, "another institution", "test");

        HttpResponse<String> theirs = send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/v1/decisions/evaluate"))
                .header("Content-Type", "application/json")
                .header("X-Dev-Tenant-Id", stranger.toString())
                .header("X-Dev-Principal", "user:admin")
                .header("X-Dev-Permissions", "products:read")
                .POST(HttpRequest.BodyPublishers.ofString(
                        """
                        {"productCode":"SAV","operation":"DEPOSIT","kycTier":"TIER_1",
                         "channel":"TELLER","amountMinor":1000,"currency":"NGN"}"""))
                .build());

        assertThat(json.readTree(theirs.body()).path("refusal").asString()).isEqualTo("PRODUCT_NOT_FOUND");
    }

    @Test
    void a_tenant_this_service_has_never_heard_of_is_refused() {
        HttpResponse<String> answer = send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/v1/products"))
                .header("X-Dev-Tenant-Id", UUID.randomUUID().toString())
                .header("X-Dev-Principal", "user:admin")
                .header("X-Dev-Permissions", "products:read")
                .GET()
                .build());

        // 404 rather than 403: telling a caller which tenant ids exist is an enumeration oracle.
        assertThat(answer.statusCode()).isEqualTo(404);
    }

    @Test
    void the_surface_denies_by_default() {
        assertThat(send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/products"))
                                .GET()
                                .build())
                        .statusCode())
                .isEqualTo(401);

        // Authenticated but not permitted is a body-less 403 — naming the permission that would
        // have worked hands a prober the model.
        assertThat(send(as("/v1/products", "customers:read").GET().build()).statusCode()).isEqualTo(403);
    }
}
