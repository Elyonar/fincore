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
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * The UI runway's read surface (ui-runway.md §3): every screen-opener, probed the way every list
 * here is probed — deny-by-default, cross-tenant invisibility, keyset pagination that stays
 * stable, and the two ledger proxies preserving the ledger's answers rather than reshaping them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FakeServices.class)
class UiReadsApiTest {

    private static final String ALL =
            "customers:read,transfers:read,tills:read,approvals:check,approvals:make,cash:transact";

    @Autowired private TenantRegistry tenantRegistry;
    @Autowired private FakeServices.FakeCustomers customers;
    @LocalServerPort private int port;
    @Autowired @Qualifier("workerJdbcTemplate") private JdbcTemplate workerDb;
    @Autowired private org.elyonar.fincore.core.orchestration.internal.saga.TillRecords tills;
    @Autowired private org.elyonar.fincore.core.orchestration.internal.approval.ApprovalRecords approvals;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper mapper = JsonMapper.builder().build();

    private static HttpServer ledger;
    private static final AtomicInteger accountStatus = new AtomicInteger(200);
    private static final AtomicInteger entriesStatus = new AtomicInteger(200);
    private static final String STATEMENT_BODY =
            "{\"accountId\":\"x\",\"period\":{\"from\":\"2026-08-01\",\"to\":\"2026-08-31\"},"
                    + "\"openingMinor\":\"0\",\"closingMinor\":\"100000\",\"interim\":true,\"lines\":[]}";

    /** What the stubbed Ledger answers a statement read with. Reset per test. */
    private static final AtomicReference<String> statementBody = new AtomicReference<>(STATEMENT_BODY);

    private UUID tenantId;
    private UUID customerId;
    private UUID customerAccount;
    private UUID tillAccount;
    private UUID tillId;
    private UUID feeAccount;

    @BeforeAll
    static void startLedger() throws IOException {
        ledger = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ledger.createContext(
                "/",
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    int status;
                    String body;
                    if ("POST".equals(exchange.getRequestMethod())) {
                        status = 201;
                        body = "{\"transactionId\":\"" + UUID.randomUUID() + "\"}";
                    } else if (path.endsWith("/entries")) {
                        status = entriesStatus.get();
                        body = status == 200 ? statementBody.get() : "{\"code\":\"NOT_FOUND\"}";
                    } else {
                        status = accountStatus.get();
                        body =
                                status == 200
                                        ? "{\"accountId\":\"x\",\"currentMinor\":\"12345\","
                                                + "\"availableMinor\":\"12000\",\"holdsMinor\":\"345\"}"
                                        : "{\"code\":\"KABOOM\"}";
                    }
                    exchange.getRequestBody().readAllBytes();
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length);
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
    static void config(DynamicPropertyRegistry registry) {
        registry.add("fincore.core.ledger.base-url", () -> "http://127.0.0.1:" + ledger.getAddress().getPort());
        registry.add("fincore.core.worker.interval-ms", () -> "3600000");
        registry.add("fincore.core.outbox.relay.interval-ms", () -> "3600000");
        registry.add("fincore.test.context", () -> "ui-reads");
    }

    @BeforeEach
    void seed() {
        tenantId = UUID.randomUUID();
        tenantRegistry.register(tenantId, "test tenant", "test");
        customerId = UUID.randomUUID();
        customerAccount = UUID.randomUUID();
        tillAccount = UUID.randomUUID();
        feeAccount = UUID.randomUUID();
        accountStatus.set(200);
        entriesStatus.set(200);

        // Customer is a deployable now (ADR 0020). The 360 view below is still Core's — it
        // joins what Customer knows a person holds with what the Ledger says is in it — so the
        // premise is stated here and the composition is what gets asserted.
        customers.clear();
        // Scoped to *this* tenant explicitly, because one of the tests below exists to prove
        // another tenant sees nothing. A holding that matched any tenant would let that test
        // pass by leaking.
        customers.eligible(customerId, "TIER_2").holds(tenantId, customerId, customerAccount, "P", "NGN");

        // Still Core's, and still real: a till is orchestration's own table, and the reads below
        // are about what went through it.
        tillId = tills.open(tenantId, "BR-01", null, tillAccount, "NGN", "user:teller-1");
    }

    // ------------------------------------------------------------------ harness

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

    private JsonNode getJson(String path, String permissions, String principal) {
        var response = send(as(path, permissions, principal).GET().build());
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        return mapper.readTree(response.body());
    }

    private HttpResponse<String> post(String path, String permissions, String principal, String body) {
        return send(as(path, permissions, principal).POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    // Customer search left Core with ADR 0020: `/v1/customers?q=` is the Customer service's
    // surface and is tested there. Core keeps the 360 view below, which is a composition of
    // Customer's answer and the Ledger's, and therefore genuinely Core's to prove.

    @Test
    void the_customer_360_joins_held_accounts_with_the_ledgers_balances() {
        JsonNode view = getJson("/v1/customers/" + customerId + "/accounts", ALL, "user:teller");
        assertThat(view.get("accounts")).hasSize(1);
        JsonNode account = view.get("accounts").get(0);
        assertThat(account.get("ledgerAccountId").asString()).isEqualTo(customerAccount.toString());
        assertThat(account.get("currency").asString()).isEqualTo("NGN");
        assertThat(account.get("currentMinor").asString()).isEqualTo("12345");
        assertThat(account.get("availableMinor").asString()).isEqualTo("12000");
    }

    @Test
    void a_balance_the_ledger_cannot_answer_is_a_503_never_zero() {
        accountStatus.set(500);
        assertThat(
                        send(as("/v1/customers/" + customerId + "/accounts", ALL, "user:teller").GET().build())
                                .statusCode())
                .isEqualTo(503);
    }

    // ------------------------------------------------------------------ statement proxy

    /**
     * The statement is the Ledger's document, and everything a client depends on survives the trip:
     * the period, the opening and closing figures, the interim label, the lines and their order,
     * and an indistinguishable 404.
     *
     * <p>It is no longer byte-identical, and that is the one deliberate exception. Each line whose
     * posting this institution originated gains a {@code reference} — the name on the customer's
     * receipt — because without it a statement line and the reference somebody reads down the
     * telephone are unrelated strings and the counter cannot get from one to the other. Nothing is
     * removed and no figure is recomputed; a line we did not originate simply has no reference.
     */
    @Test
    void the_statement_passes_through_intact_and_gains_our_reference() {
        var ok =
                send(as("/v1/accounts/" + customerAccount + "/statement?from=2026-08-01&to=2026-08-31",
                                ALL, "user:teller")
                        .GET()
                        .build());
        assertThat(ok.statusCode()).isEqualTo(200);

        var document = mapper.readTree(ok.body());
        assertThat(document.get("openingMinor").asString()).isEqualTo("0");
        assertThat(document.get("closingMinor").asString()).isEqualTo("100000");
        assertThat(document.get("interim").asBoolean()).isTrue();
        assertThat(document.get("period").get("from").asString()).isEqualTo("2026-08-01");
        // No posting of ours is in this stub's lines, so nothing is annotated and the body stands.
        assertThat(ok.body()).isEqualTo(STATEMENT_BODY);

        entriesStatus.set(404);
        assertThat(
                        send(as("/v1/accounts/" + customerAccount + "/statement?from=2026-08-01&to=2026-08-31",
                                        ALL, "user:teller")
                                .GET()
                                .build())
                                .statusCode())
                .isEqualTo(404);
    }

    /**
     * A line whose posting we originated carries the reference from its receipt.
     *
     * <p>This is the join that makes a customer's question answerable: they read out
     * {@code TXN-20260811-00001}, and the line on their statement says the same thing. Asserted
     * against a posting actually made through the counter rather than a hand-written row, because
     * the mapping is from the Ledger's transaction id to ours and a fixture proves nothing about it.
     */
    @Test
    void a_statement_line_we_posted_carries_our_reference() {
        String key = "stmt-" + UUID.randomUUID();
        var deposit =
                post(
                        "/v1/deposits", "cash:transact", "user:teller-1",
                        ("{\"idempotencyKey\":\"%s\",\"customerId\":\"%s\",\"customerAccountId\":\"%s\","
                                        + "\"tillId\":\"%s\",\"feeAccountId\":\"%s\",\"amountMinor\":100000,"
                                        + "\"currency\":\"NGN\",\"productCode\":\"P\",\"channel\":\"TELLER\","
                                        + "\"description\":\"d\"}")
                                .formatted(key, customerId, customerAccount, tillId, feeAccount));
        assertThat(deposit.statusCode()).isEqualTo(201);
        var posted = mapper.readTree(deposit.body());
        String ledgerTransactionId = posted.get("ledgerTransactionId").asString();
        String reference = posted.get("reference").asString();

        statementBody.set(
                ("{\"accountId\":\"x\",\"period\":{\"from\":\"2026-08-01\",\"to\":\"2026-08-31\"},"
                                + "\"openingMinor\":\"0\",\"closingMinor\":\"100000\",\"interim\":true,"
                                + "\"lines\":[{\"entryId\":\"e1\",\"transactionId\":\"%s\","
                                + "\"direction\":\"CREDIT\",\"amountMinor\":\"100000\"}]}")
                        .formatted(ledgerTransactionId));

        var statement =
                send(as("/v1/accounts/" + customerAccount + "/statement?from=2026-08-01&to=2026-08-31",
                                ALL, "user:teller")
                        .GET()
                        .build());

        assertThat(statement.statusCode()).isEqualTo(200);
        var line = mapper.readTree(statement.body()).get("lines").get(0);
        assertThat(line.get("reference").asString()).isEqualTo(reference);
        // Everything the Ledger said about the line is still there, unchanged.
        assertThat(line.get("amountMinor").asString()).isEqualTo("100000");
        assertThat(line.get("direction").asString()).isEqualTo("CREDIT");

        statementBody.set(STATEMENT_BODY);
    }

    // ------------------------------------------------------------------ till activity

    @Test
    void the_tills_day_lists_its_sagas_with_a_net_position() {
        String key = "dep-" + UUID.randomUUID();
        var deposit =
                post(
                        "/v1/deposits", "cash:transact", "user:teller-1",
                        ("{\"idempotencyKey\":\"%s\",\"customerId\":\"%s\",\"customerAccountId\":\"%s\","
                                        + "\"tillId\":\"%s\",\"feeAccountId\":\"%s\",\"amountMinor\":100000,"
                                        + "\"currency\":\"NGN\",\"productCode\":\"P\",\"channel\":\"TELLER\","
                                        + "\"description\":\"counter\"}")
                                .formatted(key, customerId, customerAccount, tillId, feeAccount));
        assertThat(deposit.statusCode()).isEqualTo(201);

        String today = LocalDate.now(ZoneOffset.UTC).toString();
        JsonNode day = getJson("/v1/tills/" + tillId + "/activity?date=" + today, ALL, "user:supervisor");
        assertThat(day.get("movements")).hasSize(1);
        assertThat(day.get("movements").get(0).get("amountMinor").asString()).isEqualTo("100000");
        long in = Long.parseLong(day.get("completedInMinor").asString());
        long out = Long.parseLong(day.get("completedOutMinor").asString());
        assertThat(Long.parseLong(day.get("netMinor").asString())).isEqualTo(in - out);

        // A day nothing happened is an empty day, not an error.
        assertThat(getJson("/v1/tills/" + tillId + "/activity?date=2020-01-01", ALL, "user:supervisor")
                        .get("movements"))
                .isEmpty();
    }

    // ------------------------------------------------------------------ approvals queue

    @Test
    void the_checkers_queue_lists_pending_approvals_oldest_first() {
        approvals.raise(tenantId, UUID.randomUUID(), 250_000, "user:maker", "branch-01");
        JsonNode queue = getJson("/v1/approvals/pending", ALL, "user:checker");
        assertThat(queue.get("approvals")).hasSize(1);
        assertThat(queue.get("approvals").get(0).get("madeBy").asString()).isEqualTo("user:maker");
        assertThat(queue.get("approvals").get(0).get("amountMinor").asString()).isEqualTo("250000");
    }

    // ------------------------------------------------------------------ the standing probes

    @Test
    void every_new_read_denies_by_default() {
        String[][] probes = {
            {"/v1/customers/" + customerId + "/accounts", "tills:read"},
            {"/v1/accounts/" + customerAccount + "/statement?from=2026-08-01&to=2026-08-31", "customers:read"},
            {"/v1/tills/" + tillId + "/activity?date=2026-08-08", "customers:read"},
            {"/v1/approvals/pending", "approvals:make"},
        };
        for (String[] probe : probes) {
            assertThat(send(as(probe[0], probe[1], "user:x").GET().build()).statusCode())
                    .as(probe[0])
                    .isEqualTo(403);
        }
    }

    @Test
    void another_tenants_reads_are_empty_or_invisible_never_leaky() {
        UUID otherTenant = UUID.randomUUID();
        tenantRegistry.register(otherTenant, "other", "test");
        var foreign =
                send(
                        HttpRequest.newBuilder(
                                        URI.create("http://localhost:" + port + "/v1/customers/" + customerId + "/accounts"))
                                .header("X-Dev-Tenant-Id", otherTenant.toString())
                                .header("X-Dev-Principal", "user:intruder")
                                .header("X-Dev-Permissions", ALL)
                                .GET()
                                .build());
        assertThat(foreign.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(foreign.body()).get("accounts")).isEmpty();

        // The foreign *search* probe went with `/v1/customers?q=` to the Customer service, which
        // enforces the same isolation over its own database and is tested there.
    }
}
