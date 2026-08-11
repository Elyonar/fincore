package org.elyonar.fincore.core.app;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Opening an account, and the numbering behind it.
 *
 * <p>Before this surface existed a customer could be created and could not hold an account:
 * {@code POST /v1/customers/{id}/accounts} linked a ledger account UUID the caller had to already
 * possess, and nothing on the platform could produce one. These assertions are about the two
 * halves that closed it — that the ledger account is really opened, and that the number a customer
 * is told is unique, formed by the institution's own rule, and never handed out twice.
 *
 * <p>The ledger is stubbed here for the same reason the saga suites stub it: this is Core's
 * composition being tested, not the ledger's account model, which has its own suite one deployable
 * away.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("account opening — a customer can hold an account, numbered by the institution")
class AccountOpeningApiTest {

    @Autowired private TenantRegistry tenantRegistry;

    /**
     * A ledger that opens whatever it is asked to, and remembers what it was asked.
     *
     * <p>Stubbed for the same reason every saga suite stubs it: what is under test is Core's
     * composition — open, then link, then number — not the ledger's account model, which has its
     * own suite one deployable away.
     */
    private static HttpServer ledger;

    private static final AtomicReference<String> lastRequest = new AtomicReference<>();

    @BeforeAll
    static void startLedger() throws IOException {
        ledger = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ledger.createContext("/", exchange -> {
            lastRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("{\"accountId\":\"" + UUID.randomUUID() + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        ledger.start();
    }

    @AfterAll
    static void stopLedger() {
        ledger.stop(0);
    }

    @LocalServerPort private int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired @Qualifier("productJdbcTemplate") private JdbcTemplate productDb;
    @Autowired @Qualifier("productTransactionManager") private PlatformTransactionManager productTx;

    private UUID tenantId;

    // org:manage because the numbering rule is institution configuration, not customer business —
    // the same grant that administers organizational units, and asserted separately below.
    private static final String ALL = "customers:create,customers:read,customers:link,org:manage";

    @DynamicPropertySource
    static void quiet(DynamicPropertyRegistry registry) {
        registry.add("fincore.core.ledger.base-url", () -> "http://127.0.0.1:" + ledger.getAddress().getPort());
        registry.add("fincore.core.worker.interval-ms", () -> "3600000");
        registry.add("fincore.core.outbox.relay.interval-ms", () -> "3600000");
    }

    @BeforeEach
    void freshTenant() {
        tenantId = UUID.randomUUID();
        tenantRegistry.register(tenantId, "test tenant", "test");

        // The catalogue must know 'P' before an account can be held under it — opening now refuses
        // a code the catalogue has never heard of. A DRAFT version suffices deliberately: an
        // unpublished product is configuration in progress, not a typo.
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
                            productDb.update(
                                    "INSERT INTO product.product_versions (tenant_id, product_id, version,"
                                            + " status, created_by) VALUES (?,?,1,'DRAFT','user:author')",
                                    tenantId, productId);
                        });
    }

    // ------------------------------------------------------------------ harness

    private HttpResponse<String> send(String method, String path, String body) {
        return sendAs(ALL, method, path, body);
    }

    private HttpResponse<String> sendAs(String permissions, String method, String path, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Dev-Tenant-Id", tenantId.toString())
                .header("X-Dev-Principal", "user:ada")
                .header("X-Dev-Permissions", permissions);
        request = "GET".equals(method)
                ? request.GET()
                : request.method(method, HttpRequest.BodyPublishers.ofString(body));
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode json(HttpResponse<String> response) {
        return mapper.readTree(response.body());
    }

    private String newCustomer(String externalRef) {
        String body = externalRef == null
                ? "{\"fullName\":\"Amaka Obi\",\"kycTier\":\"TIER_1\"}"
                : "{\"externalRef\":\"%s\",\"fullName\":\"Amaka Obi\",\"kycTier\":\"TIER_1\"}".formatted(externalRef);
        HttpResponse<String> created = send("POST", "/v1/customers", body);
        assertThat(created.statusCode()).isEqualTo(201);
        return json(created).get("customerId").asString();
    }

    // ------------------------------------------------------------------ numbering

    @Test
    @DisplayName("a customer created without a reference is numbered by the institution")
    void the_institution_numbers_its_own_customers() {
        JsonNode first = json(send("GET", "/v1/customers/" + newCustomer(null), null));
        JsonNode second = json(send("GET", "/v1/customers/" + newCustomer(null), null));

        // Ten digits by default, because that is the shape of the number a Nigerian customer is
        // used to quoting. Sequential, and never the same one twice.
        assertThat(first.get("externalRef").asString()).hasSize(10).containsOnlyDigits();
        assertThat(second.get("externalRef").asString()).isNotEqualTo(first.get("externalRef").asString());
    }

    @Test
    @DisplayName("an institution that already numbers its customers keeps its own")
    void a_supplied_reference_is_honoured() {
        JsonNode read = json(send("GET", "/v1/customers/" + newCustomer("ACME-0042"), null));
        assertThat(read.get("externalRef").asString()).isEqualTo("ACME-0042");
    }

    @Test
    @DisplayName("an institution that already numbers its accounts keeps its own")
    void a_supplied_account_number_is_honoured() {
        // The same shape `externalRef` already has, and for the same reason. An institution moving
        // an existing book onto this platform arrives with numbers printed on passbooks and known
        // to the settlement switch; a column that could only be generated would renumber every one
        // of them on migration day, and every inbound payment would resolve to nothing.
        JsonNode opened = json(send(
                "POST",
                "/v1/customers/" + newCustomer(null) + "/accounts/open",
                "{\"currency\":\"NGN\",\"productCode\":\"P\",\"accountNumber\":\"LEGACY-77301\"}"));
        assertThat(opened.get("accountNumber").asString()).isEqualTo("LEGACY-77301");

        // Blank still means "give me the next one", so the ordinary counter path is untouched.
        JsonNode generated = json(send(
                "POST", "/v1/customers/" + newCustomer(null) + "/accounts/open", "{\"currency\":\"NGN\",\"productCode\":\"P\"}"));
        assertThat(generated.get("accountNumber").asString()).isNotEqualTo("LEGACY-77301");
    }

    @Test
    @DisplayName("a number another live account already carries is refused as its own thing")
    void a_duplicate_account_number_is_refused() {
        String body = "{\"currency\":\"NGN\",\"productCode\":\"P\",\"accountNumber\":\"LEGACY-88402\"}";
        assertThat(send("POST", "/v1/customers/" + newCustomer(null) + "/accounts/open", body).statusCode())
                .isEqualTo(201);

        // Not ACCOUNT_ALREADY_HELD: that means this customer already holds the account, this means
        // the number belongs to somebody else. A caller that cannot tell them apart cannot write
        // either sentence.
        HttpResponse<String> clash =
                send("POST", "/v1/customers/" + newCustomer(null) + "/accounts/open", body);
        assertThat(clash.statusCode()).isEqualTo(409);
        assertThat(clash.body()).contains("ACCOUNT_NUMBER_TAKEN");
    }

    @Test
    @DisplayName("the numbering rule is readable and settable, and the preview shows its effect")
    void numbering_can_be_changed() {
        JsonNode before = json(send("GET", "/v1/customer-numbering", null));
        assertThat(before).hasSize(2);

        HttpResponse<String> changed = send(
                "PUT", "/v1/customer-numbering/ACCOUNT", "{\"prefix\":\"ACC-\",\"width\":6,\"nextValue\":500}");
        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(json(changed).get("preview").asString()).isEqualTo("ACC-000500");

        // A width outside the column's CHECK is refused before it reaches the database, so the
        // caller gets a sentence rather than a constraint violation.
        assertThat(send("PUT", "/v1/customer-numbering/ACCOUNT", "{\"prefix\":\"\",\"width\":99,\"nextValue\":1}")
                        .statusCode())
                .isEqualTo(422);
        assertThat(send("PUT", "/v1/customer-numbering/NONSENSE", "{\"prefix\":\"\",\"width\":6,\"nextValue\":1}")
                        .statusCode())
                .isEqualTo(422);
    }

    @Test
    @DisplayName("the numbering rule is institution configuration, so customer grants cannot move it")
    void numbering_changes_require_the_institution_grant() {
        // Reading stays with customers:read; writing costs org:manage. A teller-grade token that
        // can create customers must not be able to move the counter every account number comes
        // from — a rewind there collides live numbers, which is a money-adjacent control.
        HttpResponse<String> refused = sendAs(
                "customers:create,customers:read,customers:link",
                "PUT", "/v1/customer-numbering/ACCOUNT", "{\"prefix\":\"\",\"width\":10,\"nextValue\":500}");
        assertThat(refused.statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("a series never winds backwards below numbers it has already issued")
    void a_numbering_rewind_is_refused() {
        assertThat(send("PUT", "/v1/customer-numbering/ACCOUNT", "{\"prefix\":\"\",\"width\":10,\"nextValue\":500}")
                        .statusCode())
                .isEqualTo(200);

        // 100 < 500 re-issues four hundred numbers that may already be on passbooks: every future
        // opening then collides as ACCOUNT_NUMBER_TAKEN until the counter walks past them.
        HttpResponse<String> rewind =
                send("PUT", "/v1/customer-numbering/ACCOUNT", "{\"prefix\":\"\",\"width\":10,\"nextValue\":100}");
        assertThat(rewind.statusCode()).isEqualTo(422);
        JsonNode error = json(rewind);
        assertThat(error.get("code").asString()).isEqualTo("COMMAND_INVALID");
        assertThat(error.get("details").get("field").asString()).isEqualTo("nextValue");
        assertThat(error.get("details").get("current").asString()).isEqualTo("500");

        // Forward, including standing still, stays legal — that is how migration day sets a start.
        assertThat(send("PUT", "/v1/customer-numbering/ACCOUNT", "{\"prefix\":\"\",\"width\":10,\"nextValue\":500}")
                        .statusCode())
                .isEqualTo(200);
    }

    // ------------------------------------------------------------------ opening

    @Test
    @DisplayName("opening gives the customer a ledger account and a number to quote")
    void an_account_is_opened_and_numbered() {
        String customerId = newCustomer(null);

        HttpResponse<String> opened =
                send("POST", "/v1/customers/" + customerId + "/accounts/open", "{\"currency\":\"NGN\",\"productCode\":\"P\"}");
        assertThat(opened.statusCode()).isEqualTo(201);

        JsonNode account = json(opened);
        assertThat(account.get("ledgerAccountId").asString()).isNotBlank();
        assertThat(account.get("accountNumber").asString()).hasSize(10).containsOnlyDigits();
        assertThat(account.get("currency").asString()).isEqualTo("NGN");
        assertThat(account.get("role").asString()).isEqualTo("PRIMARY");
        // What the account is held under. The money path reads its pricing from here rather than
        // from the request body, so an account that did not record it could not transact at all.
        assertThat(account.get("productCode").asString()).isEqualTo("P");

        // And the customer now reads back holding it — the link, not just the ledger account.
        JsonNode profile = json(send("GET", "/v1/customers/" + customerId, null));
        assertThat(profile.get("accounts")).hasSize(1);

        // What the ledger was asked for: a CUSTOMER account that refuses negatives, referenced by
        // the institution's own customer number and never by a name — the ledger holds no PII.
        assertThat(lastRequest.get())
                .contains("\"type\":\"CUSTOMER\"")
                .contains("\"allowNegative\":false")
                .contains("\"customerRef\":\"customer:")
                .doesNotContain("Amaka");
    }

    @Test
    @DisplayName("two accounts never share a number")
    void numbers_are_not_handed_out_twice() {
        String first = json(send(
                        "POST",
                        "/v1/customers/" + newCustomer(null) + "/accounts/open",
                        "{\"currency\":\"NGN\",\"productCode\":\"P\"}"))
                .get("accountNumber")
                .asString();
        String second = json(send(
                        "POST",
                        "/v1/customers/" + newCustomer(null) + "/accounts/open",
                        "{\"currency\":\"NGN\",\"productCode\":\"P\"}"))
                .get("accountNumber")
                .asString();

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    @DisplayName("what cannot be opened is refused with a reason, not a stack trace")
    void refusals_say_what_is_wrong() {
        assertThat(send(
                                "POST",
                                "/v1/customers/" + UUID.randomUUID() + "/accounts/open",
                                "{\"currency\":\"NGN\",\"productCode\":\"P\"}")
                        .statusCode())
                .isEqualTo(422);

        HttpResponse<String> badCurrency =
                send("POST", "/v1/customers/" + newCustomer(null) + "/accounts/open", "{\"currency\":\"NAIRA\",\"productCode\":\"P\"}");
        assertThat(badCurrency.statusCode()).isEqualTo(422);
        assertThat(json(badCurrency).get("code").asString()).isEqualTo("ACCOUNT_NOT_OPENED");

        // An account with no product is an account that can never transact — the money path prices
        // by the product the account records, and refuses when there is none. Catching it at the
        // one moment somebody knows the answer beats discovering it at a counter.
        HttpResponse<String> noProduct =
                send("POST", "/v1/customers/" + newCustomer(null) + "/accounts/open", "{\"currency\":\"NGN\"}");
        assertThat(noProduct.statusCode()).isEqualTo(422);
        assertThat(json(noProduct).get("message").asString()).contains("productCode");
    }

    @Test
    @DisplayName("a product code the catalogue has never heard of is refused before the account exists")
    void an_unknown_product_code_is_refused() {
        // A typo'd code used to be accepted verbatim, and nothing can edit product_code on a live
        // link — so the account opened, and then every transaction on it refused with
        // PRODUCT_NOT_FOUND, forever. The same code now answers here, at the one moment the
        // mistake is a keystroke rather than a bricked account.
        String customerId = newCustomer(null);
        HttpResponse<String> refused = send(
                "POST",
                "/v1/customers/" + customerId + "/accounts/open",
                "{\"currency\":\"NGN\",\"productCode\":\"PP\"}");

        assertThat(refused.statusCode()).isEqualTo(422);
        JsonNode error = json(refused);
        assertThat(error.get("code").asString()).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(error.get("details").get("field").asString()).isEqualTo("productCode");
        assertThat(error.get("details").get("supplied").asString()).isEqualTo("PP");

        // Refused before anything was created: the customer holds nothing to reconcile away.
        JsonNode profile = json(send("GET", "/v1/customers/" + customerId, null));
        assertThat(profile.get("accounts")).isEmpty();
    }

    @Test
    @DisplayName("two tellers opening the first two accounts at once are handed different numbers")
    void the_first_two_numbers_are_claimed_once_each() throws Exception {
        // The first use of a series is its own race: no numbering row exists yet, so both claimants
        // used to run INSERT … ON CONFLICT DO NOTHING and both return number 1 — the conflict
        // loser never re-read the row the winner had seeded. The loser now re-runs the UPDATE and
        // takes the next value instead.
        String customerA = newCustomer(null);
        String customerB = newCustomer(null);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Future<HttpResponse<String>> first = pool.submit(() -> {
                go.await();
                return send("POST", "/v1/customers/" + customerA + "/accounts/open",
                        "{\"currency\":\"NGN\",\"productCode\":\"P\"}");
            });
            java.util.concurrent.Future<HttpResponse<String>> second = pool.submit(() -> {
                go.await();
                return send("POST", "/v1/customers/" + customerB + "/accounts/open",
                        "{\"currency\":\"NGN\",\"productCode\":\"P\"}");
            });
            go.countDown();
            HttpResponse<String> a = first.get(30, java.util.concurrent.TimeUnit.SECONDS);
            HttpResponse<String> b = second.get(30, java.util.concurrent.TimeUnit.SECONDS);

            // Both succeed — the race costs nobody their account — and the numbers differ.
            assertThat(a.statusCode()).isEqualTo(201);
            assertThat(b.statusCode()).isEqualTo(201);
            String numberA = json(a).get("accountNumber").asString();
            String numberB = json(b).get("accountNumber").asString();
            assertThat(numberA).isNotEqualTo(numberB);
            assertThat(java.util.Set.of(numberA, numberB)).containsExactlyInAnyOrder("0000000001", "0000000002");
        } finally {
            pool.shutdownNow();
        }
    }
}
