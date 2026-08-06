package org.elyonar.fincore.core.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The API contract, over real HTTP.
 *
 * <p>Through the whole filter chain rather than by calling a service: the status codes <em>are</em>
 * the contract a channel integrates against, and the retry rule is stated in terms of them. Asserting
 * the codes anywhere but at the wire would be asserting an intention.
 *
 * <p>The case this file exists for is {@code an_unknown_outcome_answers_503}. Every other guarantee
 * in the outcome protocol depends on a caller retrying the same key, and a caller only does that if
 * the status says to.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransferApiTest {

    private static HttpServer ledger;
    private static final AtomicInteger ledgerStatus = new AtomicInteger(201);
    private static final AtomicReference<String> ledgerBody = new AtomicReference<>("{}");

    @LocalServerPort private int port;

    /**
     * The JDK's client, not a Spring one.
     *
     * <p>An API contract test should exercise the wire the way an integrator would, and a client
     * that knows nothing about this application cannot accidentally paper over a serialization or
     * status-code difference.
     */
    private final HttpClient http = HttpClient.newHttpClient();
    @Autowired @Qualifier("customerJdbcTemplate") private JdbcTemplate customerDb;
    @Autowired @Qualifier("productJdbcTemplate") private JdbcTemplate productDb;
    @Autowired @Qualifier("customerTransactionManager") private PlatformTransactionManager customerTx;
    @Autowired @Qualifier("productTransactionManager") private PlatformTransactionManager productTx;

    private UUID tenantId;
    private UUID customerId;
    private UUID fromAccount;
    // Stable per test: a replay is only a replay if the body is byte-identical, and the
    // fingerprint covers the accounts.
    private UUID toAccount;
    private UUID feeAccount;

    @BeforeAll
    static void startLedger() throws IOException {
        ledger = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ledger.createContext(
                "/",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    byte[] bytes = ledgerBody.get().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(ledgerStatus.get(), bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        ledger.start();
    }

    @AfterAll
    static void stopLedger() {
        ledger.stop(0);
    }

    @DynamicPropertySource
    static void pointAtStub(DynamicPropertyRegistry registry) {
        registry.add("fincore.core.ledger.base-url", () -> "http://127.0.0.1:" + ledger.getAddress().getPort());
        registry.add("fincore.core.worker.interval-ms", () -> "3600000");
    }

    @BeforeEach
    void seed() {
        tenantId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        fromAccount = UUID.randomUUID();
        toAccount = UUID.randomUUID();
        feeAccount = UUID.randomUUID();
        ledgerStatus.set(201);
        ledgerBody.set("{\"transactionId\":\"" + UUID.randomUUID() + "\"}");

        new TransactionTemplate(customerTx)
                .executeWithoutResult(
                        s -> {
                            customerDb.queryForObject(
                                    "SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
                            customerDb.update(
                                    "INSERT INTO customer.customers (id, tenant_id, external_ref, full_name, kyc_tier)"
                                            + " VALUES (?,?,?,?, 'TIER_2')",
                                    customerId, tenantId, "C-" + UUID.randomUUID(), "Ada");
                            customerDb.update(
                                    "INSERT INTO customer.customer_accounts (tenant_id, customer_id,"
                                            + " ledger_account_id, currency) VALUES (?,?,?, 'NGN')",
                                    tenantId, customerId, fromAccount);
                        });

        new TransactionTemplate(productTx)
                .executeWithoutResult(
                        s -> {
                            productDb.queryForObject(
                                    "SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
                            UUID productId =
                                    productDb.queryForObject(
                                            "INSERT INTO product.products (tenant_id, code, name, type)"
                                                    + " VALUES (?, 'P', 'P', 'SAVINGS') RETURNING id",
                                            UUID.class, tenantId);
                            UUID versionId =
                                    productDb.queryForObject(
                                            "INSERT INTO product.product_versions (tenant_id, product_id, version,"
                                                    + " status, created_by, published_by) VALUES (?,?,1,'PUBLISHED','user:author','user:publisher') RETURNING id",
                                            UUID.class, tenantId, productId);
                            productDb.update(
                                    "INSERT INTO product.limit_rules (tenant_id, product_version_id, kyc_tier,"
                                            + " channel, limit_type, max_amount_minor, currency)"
                                            + " VALUES (?,?, 'TIER_2', 'API', 'PER_TXN', 5000000, 'NGN')",
                                    tenantId, versionId);
                        });
    }

    /** Dev-mode identity headers. The resolver only starts at all because `test` is sanctioned. */
    private HttpRequest.Builder authed(String path, String permissions) {
        HttpRequest.Builder b =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .header("X-Dev-Tenant-Id", tenantId.toString())
                        .header("X-Dev-Principal", "user:ada.o@branch-01");
        return permissions == null ? b : b.header("X-Dev-Permissions", permissions);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String transferBody(String key, long amountMinor) {
        return """
               {"idempotencyKey":"%s","customerId":"%s","fromAccountId":"%s","toAccountId":"%s",
                "feeAccountId":"%s","amountMinor":%d,"currency":"NGN","productCode":"P",
                "channel":"API","description":"d"}
               """
                .formatted(key, customerId, fromAccount, toAccount, feeAccount, amountMinor);
    }

    private HttpResponse<String> post(String key, long amount, String permissions) {
        return send(
                authed("/v1/transfers", permissions)
                        .POST(HttpRequest.BodyPublishers.ofString(transferBody(key, amount)))
                        .build());
    }

    // ------------------------------------------------------------------ the one

    @Test
    void an_unknown_outcome_answers_503_and_names_the_transaction_to_poll() {
        // The contract everything else rests on. A 5xx is what obliges the caller to retry the same
        // key; a 202 would let it record "submitted" and stop asking, while the platform still does
        // not know whether the money moved.
        ledgerStatus.set(500);
        ledgerBody.set("{\"code\":\"INTERNAL\"}");

        HttpResponse<String> response = post("api-unknown", 100_000, "transfers:create");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("OUTCOME_UNKNOWN").contains("transactionId");
    }

    // ---------------------------------------------------------------- the rest

    @Test
    void a_committed_transfer_answers_201_with_the_fee_that_was_applied() {
        HttpResponse<String> response = post("api-ok", 100_000, "transfers:create");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("\"state\":\"COMPLETED\"").contains("feeMinor");
    }

    @Test
    void a_refusal_answers_422_with_the_code() {
        ledgerStatus.set(422);
        ledgerBody.set("{\"code\":\"INSUFFICIENT_FUNDS\"}");

        HttpResponse<String> response = post("api-refused", 100_000, "transfers:create");

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("INSUFFICIENT_FUNDS");
    }

    @Test
    void a_limit_breach_is_refused_without_the_ledger_being_called() {
        HttpResponse<String> response = post("api-limit", 9_000_000, "transfers:create");

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("LIMIT_EXCEEDED");
    }

    @Test
    void the_same_key_with_different_economics_answers_409() {
        post("api-reuse", 100_000, "transfers:create");

        HttpResponse<String> second = post("api-reuse", 900_000, "transfers:create");

        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(second.body()).contains("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void replaying_the_same_request_answers_201_again_with_the_same_transaction() {
        String first = post("api-replay", 100_000, "transfers:create").body();
        HttpResponse<String> second = post("api-replay", 100_000, "transfers:create");

        assertThat(second.statusCode()).isEqualTo(201);
        assertThat(second.body()).isEqualTo(first);
    }

    // ---------------------------------------------------------------- denials

    @Test
    void an_unauthenticated_request_is_refused_before_it_reaches_a_handler() {
        HttpResponse<String> response =
                send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/transfers"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(transferBody("api-anon", 100_000)))
                                .build());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void a_caller_without_the_permission_is_denied_and_told_nothing_useful() {
        HttpResponse<String> response = post("api-noperm", 100_000, "transfers:read");

        assertThat(response.statusCode()).isEqualTo(403);
        // Empty body: naming the permission that would have worked hands a prober the model.
        assertThat(response.body()).isEmpty();
    }

    @Test
    void status_can_be_read_without_mutating_anything() {
        // What a crashed caller does instead of re-posting to find out.
        String created = post("api-status", 100_000, "transfers:create").body();
        String id = created.substring(created.indexOf("\"transactionId\":\"") + 17, created.indexOf("\",", created.indexOf("\"transactionId\":\"")));

        HttpResponse<String> response =
                send(authed("/v1/transactions/" + id, "transfers:read").GET().build());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("COMPLETED");
    }

    @Test
    void health_answers_without_a_caller_being_known() {
        // Readiness must not require authentication, or an orchestrator can never see the service.
        assertThat(
                        send(
                                        HttpRequest.newBuilder(
                                                        URI.create("http://localhost:" + port + "/actuator/health"))
                                                .GET()
                                                .build())
                                .statusCode())
                .isEqualTo(200);
    }
}
