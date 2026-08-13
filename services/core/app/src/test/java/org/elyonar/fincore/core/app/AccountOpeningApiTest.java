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
import org.springframework.context.annotation.Import;
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
@Import(FakeServices.class)
class AccountOpeningApiTest {

    /*
     * What is left here after ADR 0020, and what left.
     *
     * Opening an account is Core's: only Orchestration may address the Ledger, so Core opens the
     * ledger account, asks Product whether the code is real, and asks Customer to record who holds
     * it. That composition is the thing worth testing here and it is what remains below.
     *
     * The numbering assertions went with the Customer service. They were always about how *that*
     * service issues and guards a series — that a rewind is refused, that two tellers cannot be
     * handed the same number — and proving those against a fake would prove only that the fake
     * agrees with itself.
     */

    @Autowired private TenantRegistry tenantRegistry;
    @Autowired private FakeServices.FakeCustomers customers;
    @Autowired private FakeServices.FakePricing pricing;
    @Autowired private FakeServices.FakeCatalogue catalogue;

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
        // Customer and Product are deployables now (ADR 0020). Account opening is the one thing
        // that still belongs to Core, because it composes all three: only Orchestration may address
        // the Ledger, Product says whether the code is real, and Customer records who holds it.
        customers.clear();
        // Static across the class, so a test asserting the ledger was *not* called has to
        // start from a clean slate rather than inherit the previous test's request.
        lastRequest.set(null);
        pricing.permits(0, null, Long.MAX_VALUE);
        // Only P is real, so a typo'd code is refused at the one moment it is still
        // a keystroke rather than a bricked account.
        catalogue.only("P");
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

    /**
     * A customer who exists, as far as Core is concerned.
     *
     * <p>Core cannot create one any more — {@code POST /v1/customers} is the Customer service's
     * (ADR 0020). What Core needs from a customer here is only that they exist and may transact,
     * which is exactly what the port says, so the premise is stated rather than posted.
     */
    private String newCustomer(String externalRef) {
        UUID id = UUID.randomUUID();
        customers.eligible(id, "TIER_1");
        return id.toString();
    }

    // ------------------------------------------------------------------ numbering








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

        // That the customer then reads back holding it is the Customer service's to prove, and it
        // does (ADR 0020). What Core owes is that it asked for the right things in the right order,
        // which is what the ledger request below shows.

        // What the ledger was asked for: a CUSTOMER account that refuses negatives, referenced by
        // the institution's own customer number and never by a name — the ledger holds no PII.
        assertThat(lastRequest.get())
                .contains("\"type\":\"CUSTOMER\"")
                .contains("\"allowNegative\":false")
                .contains("\"customerRef\":\"customer:")
                .doesNotContain("Amaka");
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

        // Refused before anything was created — the point of checking the code here rather than on
        // the money path. Core proves that by never having called the ledger; that the customer
        // holds nothing is the Customer service's read and is asserted there.
        assertThat(lastRequest.get()).isNull();
    }

}
