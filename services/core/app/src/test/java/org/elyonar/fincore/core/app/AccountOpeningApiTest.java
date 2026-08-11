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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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

    private UUID tenantId;

    private static final String ALL = "customers:create,customers:read,customers:link";

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
    }

    // ------------------------------------------------------------------ harness

    private HttpResponse<String> send(String method, String path, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Dev-Tenant-Id", tenantId.toString())
                .header("X-Dev-Principal", "user:ada")
                .header("X-Dev-Permissions", ALL);
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
}
