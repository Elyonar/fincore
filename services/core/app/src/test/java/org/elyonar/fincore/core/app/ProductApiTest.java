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

/**
 * The product catalogue, over real HTTP.
 *
 * <p>The file's centre of gravity is maker-checker on publish. A product version holds the fee and
 * the limit, so one person drafting and publishing alone can raise a ceiling and price against it
 * unsupervised — a money control wearing configuration's clothes. {@code
 * the_author_of_a_version_may_not_publish_it} is the test that should have to be deleted first, and
 * visibly, if anyone ever decides that is acceptable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductApiTest {

    // Every tenant a test uses must be registered, because Core now refuses one it has
    // never heard of. Registering here rather than weakening the gate for tests: a guard
    // switched off under test is a guard nobody has tested.
    @Autowired private TenantRegistry tenantRegistry;

    @LocalServerPort private int port;
    private final HttpClient http = HttpClient.newHttpClient();

    private UUID tenantId;

    @DynamicPropertySource
    static void quiet(DynamicPropertyRegistry registry) {
        registry.add("fincore.core.worker.interval-ms", () -> "3600000");
        registry.add("fincore.core.outbox.relay.interval-ms", () -> "3600000");
    }

    @BeforeEach
    void freshTenant() {
        tenantId = UUID.randomUUID();
        tenantRegistry.register(tenantId, "test tenant", "test");
    }

    private HttpRequest.Builder as(String path, String permissions, String principal) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Dev-Tenant-Id", tenantId.toString())
                .header("X-Dev-Principal", principal)
                .header("X-Dev-Permissions", permissions);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String field(String json, String name) {
        int at = json.indexOf("\"" + name + "\":\"");
        if (at < 0) {
            return null;
        }
        at += name.length() + 4;
        return json.substring(at, json.indexOf('"', at));
    }

    private HttpResponse<String> createProduct(String code, String author) {
        return send(
                as("/v1/products", "products:create", author)
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        "{\"code\":\"" + code + "\",\"name\":\"Everyday Savings\",\"type\":\"SAVINGS\"}"))
                        .build());
    }

    // ------------------------------------------------------------------ creation

    @Test
    void a_new_product_arrives_as_a_draft_and_prices_nothing() {
        HttpResponse<String> created = createProduct("SAV-" + UUID.randomUUID(), "user:author");

        assertThat(created.statusCode()).isEqualTo(201);
        // A product with no versions could price nothing and would be a row that exists only to be
        // half-configured, so creation makes version 1 — as a DRAFT, which is live to nobody.
        assertThat(created.body()).contains("\"version\":1").contains("\"status\":\"DRAFT\"");
        assertThat(created.body()).contains("\"createdBy\":\"user:author\"");
    }

    @Test
    void a_product_code_is_unique_within_a_tenant_and_free_across_them() {
        String code = "SAV-" + UUID.randomUUID();
        assertThat(createProduct(code, "user:author").statusCode()).isEqualTo(201);

        HttpResponse<String> again = createProduct(code, "user:author");
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(again.body()).contains("PRODUCT_CODE_TAKEN");

        tenantId = UUID.randomUUID();

        tenantRegistry.register(tenantId, "test tenant", "test");
        assertThat(createProduct(code, "user:author").statusCode()).isEqualTo(201);
    }

    @Test
    void an_unknown_product_type_is_the_callers_error_not_a_500() {
        HttpResponse<String> created =
                send(
                        as("/v1/products", "products:create", "user:author")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"code\":\"X-" + UUID.randomUUID()
                                                        + "\",\"name\":\"Mystery\",\"type\":\"MORTGAGE\"}"))
                                .build());

        assertThat(created.statusCode()).isEqualTo(422);
        assertThat(created.body()).contains("INVALID_PRODUCT_TYPE");
    }

    // ------------------------------------------------------------------ listing

    @Test
    void the_catalogue_lists_this_tenants_products_and_no_others() {
        String code = "SAV-" + UUID.randomUUID();
        createProduct(code, "user:author");

        HttpResponse<String> mine = send(as("/v1/products", "products:read", "user:teller").GET().build());
        assertThat(mine.statusCode()).isEqualTo(200);
        assertThat(mine.body()).contains(code);

        tenantId = UUID.randomUUID();

        tenantRegistry.register(tenantId, "test tenant", "test");
        HttpResponse<String> theirs = send(as("/v1/products", "products:read", "user:teller").GET().build());
        assertThat(theirs.statusCode()).isEqualTo(200);
        assertThat(theirs.body()).doesNotContain(code);
    }

    // ------------------------------------------------------------------ publishing

    @Test
    void a_draft_becomes_live_when_someone_else_publishes_it() {
        String id = field(createProduct("SAV-" + UUID.randomUUID(), "user:author").body(), "productId");

        HttpResponse<String> published =
                send(
                        as("/v1/products/" + id + "/versions/1/publish", "products:publish", "user:supervisor")
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build());

        assertThat(published.statusCode()).isEqualTo(200);
        assertThat(published.body()).contains("\"status\":\"PUBLISHED\"");
        assertThat(published.body()).contains("\"publishedBy\":\"user:supervisor\"");
        assertThat(published.body()).contains("\"createdBy\":\"user:author\"");
    }

    @Test
    void the_author_of_a_version_may_not_publish_it() {
        String id = field(createProduct("SAV-" + UUID.randomUUID(), "user:author").body(), "productId");

        // Same principal on both sides. The publisher is taken from the token, so there is no field
        // a caller could set to route around this.
        HttpResponse<String> published =
                send(
                        as("/v1/products/" + id + "/versions/1/publish", "products:publish", "user:author")
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build());

        // 403 rather than 422: the request is well formed and the version is publishable — this
        // principal simply may not be the one to do it. The fix is a colleague, not a correction.
        assertThat(published.statusCode()).isEqualTo(403);
        assertThat(published.body()).contains("PUBLISHER_IS_AUTHOR");
    }

    @Test
    void publishing_twice_is_refused_rather_than_quietly_agreed_with() {
        String id = field(createProduct("SAV-" + UUID.randomUUID(), "user:author").body(), "productId");
        String path = "/v1/products/" + id + "/versions/1/publish";

        assertThat(
                        send(
                                        as(path, "products:publish", "user:supervisor")
                                                .POST(HttpRequest.BodyPublishers.noBody())
                                                .build())
                                .statusCode())
                .isEqualTo(200);

        HttpResponse<String> again =
                send(
                        as(path, "products:publish", "user:supervisor")
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build());

        // Agreeing would hide a mistaken assumption about which version is live, which is the
        // assumption most worth correcting.
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(again.body()).contains("VERSION_ALREADY_PUBLISHED");
    }

    @Test
    void publishing_a_version_that_does_not_exist_is_a_404() {
        String id = field(createProduct("SAV-" + UUID.randomUUID(), "user:author").body(), "productId");

        assertThat(
                        send(
                                        as("/v1/products/" + id + "/versions/7/publish", "products:publish", "user:sup")
                                                .POST(HttpRequest.BodyPublishers.noBody())
                                                .build())
                                .statusCode())
                .isEqualTo(404);
    }

    @Test
    void another_tenants_product_cannot_be_published() {
        String id = field(createProduct("SAV-" + UUID.randomUUID(), "user:author").body(), "productId");

        tenantId = UUID.randomUUID();

        tenantRegistry.register(tenantId, "test tenant", "test");
        HttpResponse<String> published =
                send(
                        as("/v1/products/" + id + "/versions/1/publish", "products:publish", "user:supervisor")
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build());

        // 404, not 403 — row-level security hid it, and saying "forbidden" would confirm it exists.
        assertThat(published.statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ permissions

    @Test
    void every_endpoint_denies_by_default() {
        String id = field(createProduct("SAV-" + UUID.randomUUID(), "user:author").body(), "productId");
        String wrong = "transfers:create";

        assertThat(send(as("/v1/products", wrong, "user:x").GET().build()).statusCode()).isEqualTo(403);
        assertThat(
                        send(as("/v1/products", wrong, "user:x").POST(HttpRequest.BodyPublishers.ofString("{}")).build())
                                .statusCode())
                .isEqualTo(403);
        assertThat(
                        send(
                                        as("/v1/products/" + id + "/versions/1/publish", wrong, "user:x")
                                                .POST(HttpRequest.BodyPublishers.noBody())
                                                .build())
                                .statusCode())
                .isEqualTo(403);
    }

    @Test
    void reading_the_catalogue_still_requires_authentication() {
        assertThat(
                        send(
                                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/products"))
                                                .GET()
                                                .build())
                                .statusCode())
                .isEqualTo(401);
    }

    // ------------------------------------------------------------------ pricing, over HTTP

    /**
     * A version can be drafted, priced and read back.
     *
     * <p>Thin on assertions and load-bearing anyway, because the thing it catches is not a wrong
     * answer but no answer at all. Two controllers mapped these six routes under different path
     * variable names — {@code {id}} against {@code {productId}} — and Spring, unable to call either
     * more specific, refused to choose and raised {@code Ambiguous handler methods} on every
     * request. The whole pricing surface returned 500 on a platform whose tests were green, because
     * every test here stopped at create and publish and nothing ever called these.
     *
     * <p>{@code ApiSurfaceCatalogTest.no_two_controllers_serve_the_same_route} now fails on the
     * collision directly. This is the other half: a route that is declared, and mapped exactly once,
     * and still cannot serve a request is a route nobody has tested.
     */
    @Test
    void a_version_can_be_drafted_priced_and_read_back() {
        String id = field(createProduct("SAV-" + UUID.randomUUID(), "user:author").body(), "productId");

        HttpResponse<String> drafted =
                send(
                        as("/v1/products/" + id + "/versions", "products:create", "user:author")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"copyFrom\":null}"))
                                .build());
        assertThat(drafted.statusCode()).isEqualTo(201);
        assertThat(drafted.body()).contains("\"version\":2");

        // A per-transaction rule, because the evaluator denies by default: a version carrying no
        // PER_TXN rule is published, signed off, and refuses every transaction under it.
        HttpResponse<String> priced =
                send(
                        as("/v1/products/" + id + "/versions/2/limit-rules", "products:create", "user:author")
                                .PUT(
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"rules\":[{\"kycTier\":\"TIER_1\",\"channel\":\"TELLER\","
                                                        + "\"limitType\":\"PER_TXN\",\"maxAmountMinor\":5000000,"
                                                        + "\"currency\":\"NGN\"}]}"))
                                .build());
        assertThat(priced.statusCode()).isEqualTo(200);

        HttpResponse<String> read =
                send(as("/v1/products/" + id + "/versions/2", "products:read", "user:author").GET().build());
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.body()).contains("\"limitType\":\"PER_TXN\"").contains("\"maxAmountMinor\":5000000");
    }

    /**
     * A version may be dated forward and never backward.
     *
     * <p>The saga records which version priced a transfer. A version dated to 2020 — before the
     * product it belongs to existed — makes every one of those records unreconstructible, and
     * reconciliation has no way to notice, because the date it is checking against is the lie.
     *
     * <p>Both halves are asserted here on purpose. The forward case failing would be a guard that
     * refuses ordinary work, which is how a guard gets switched off.
     */
    @Test
    void a_version_may_be_dated_forward_and_never_backward() {
        String id = field(createProduct("SAV-" + UUID.randomUUID(), "user:author").body(), "productId");

        HttpResponse<String> backdated =
                send(
                        as("/v1/products/" + id + "/versions/1", "products:create", "user:author")
                                .method(
                                        "PATCH",
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"effectiveFrom\":\"2020-01-01T00:00:00Z\"}"))
                                .build());
        assertThat(backdated.statusCode()).isEqualTo(422);
        assertThat(backdated.body()).contains("EFFECTIVE_FROM_IN_THE_PAST");

        HttpResponse<String> scheduled =
                send(
                        as("/v1/products/" + id + "/versions/1", "products:create", "user:author")
                                .method(
                                        "PATCH",
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"effectiveFrom\":\"2099-01-01T00:00:00Z\"}"))
                                .build());
        assertThat(scheduled.statusCode()).isEqualTo(200);
        assertThat(scheduled.body()).contains("2099-01-01");
    }
}
