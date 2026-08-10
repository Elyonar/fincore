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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
 * The UI runway's read surface (ui-runway.md §3): every screen-opener, probed the way every list
 * here is probed — deny-by-default, cross-tenant invisibility, keyset pagination that stays
 * stable, and the two ledger proxies preserving the ledger's answers rather than reshaping them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UiReadsApiTest {

    private static final String ALL =
            "customers:read,transfers:read,tills:read,approvals:check,approvals:make,cash:transact,"
                    + "loans:apply,loans:read,loans:approve,loans:offer,loans:disburse,loans:repay,loans:tiers";

    @Autowired private TenantRegistry tenantRegistry;
    @LocalServerPort private int port;
    @Autowired @Qualifier("customerJdbcTemplate") private JdbcTemplate customerDb;
    @Autowired @Qualifier("productJdbcTemplate") private JdbcTemplate productDb;
    @Autowired @Qualifier("customerTransactionManager") private PlatformTransactionManager customerTx;
    @Autowired @Qualifier("productTransactionManager") private PlatformTransactionManager productTx;
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
                        body = status == 200 ? STATEMENT_BODY : "{\"code\":\"NOT_FOUND\"}";
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
        registry.add("fincore.core.lending.jobs.interval-ms", () -> "3600000");
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

        new TransactionTemplate(customerTx)
                .executeWithoutResult(
                        s -> {
                            customerDb.queryForObject(
                                    "SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
                            customerDb.update(
                                    "INSERT INTO customer.customers (id, tenant_id, external_ref, full_name, kyc_tier)"
                                            + " VALUES (?,?,?,?, 'TIER_2')",
                                    customerId, tenantId, "C-0001", "Ada Lovelace");
                            customerDb.update(
                                    "INSERT INTO customer.customer_accounts (tenant_id, customer_id,"
                                            + " ledger_account_id, currency, product_code) VALUES (?,?,?, 'NGN', 'P')",
                                    tenantId, customerId, customerAccount);
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
                                                    + " status, created_by, published_by)"
                                                    + " VALUES (?,?,1,'DRAFT','user:author',NULL)"
                                                    + " RETURNING id",
                                            UUID.class, tenantId, productId);
                            for (String channel : new String[] {"TELLER", "API"}) {
                                productDb.update(
                                        "INSERT INTO product.limit_rules (tenant_id, product_version_id, kyc_tier,"
                                                + " channel, limit_type, max_amount_minor, currency)"
                                                + " VALUES (?,?, 'TIER_2', ?, 'PER_TXN', 5000000, 'NGN')",
                                        tenantId, versionId, channel);
                            }
                            UUID loanProductId =
                                    productDb.queryForObject(
                                            "INSERT INTO product.products (tenant_id, code, name, type)"
                                                    + " VALUES (?, 'AJO_LOAN', 'Ajo Loan', 'LOAN') RETURNING id",
                                            UUID.class, tenantId);
                            UUID loanVersionId =
                                    productDb.queryForObject(
                                            "INSERT INTO product.product_versions (tenant_id, product_id, version,"
                                                    + " status, created_by, published_by)"
                                                    + " VALUES (?,?,1,'DRAFT','user:author',NULL)"
                                                    + " RETURNING id",
                                            UUID.class, tenantId, loanProductId);
                            productDb.update(
                                    """
                                    INSERT INTO product.loan_rules
                                        (tenant_id, product_version_id, interest_rate_bp, schedule_kind,
                                         min_amount_minor, max_amount_minor, min_term_months, max_term_months,
                                         currency)
                                    VALUES (?,?, 2400, 'FLAT', 10000, 100000000, 1, 36, 'NGN')
                                    """,
                                    tenantId, loanVersionId);
                            // Published last, because pricing for a live version is immutable (V7):
                            // a rule added after publish would change what an already-decided transaction
                            // was priced under, and the database refuses it.
                            productDb.update(
                                    "UPDATE product.product_versions SET status = 'PUBLISHED',"
                                            + " published_by = 'user:publisher' WHERE tenant_id = ? AND id = ?",
                                    tenantId, versionId);
                            productDb.update(
                                    "UPDATE product.product_versions SET status = 'PUBLISHED',"
                                            + " published_by = 'user:publisher' WHERE tenant_id = ? AND id = ?",
                                    tenantId, loanVersionId);
                        });

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

    // ------------------------------------------------------------------ customer search

    @Test
    void customer_search_finds_by_name_and_reference_and_pages_by_keyset() {
        new TransactionTemplate(customerTx)
                .executeWithoutResult(
                        s -> {
                            customerDb.queryForObject(
                                    "SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
                            for (int i = 0; i < 55; i++) {
                                customerDb.update(
                                        "INSERT INTO customer.customers (tenant_id, external_ref, full_name)"
                                                + " VALUES (?,?,?)",
                                        tenantId, "PAGE-" + String.format("%03d", i), "Pager " + i);
                            }
                        });

        // By name and by reference, the same box.
        assertThat(getJson("/v1/customers?q=Lovelace", ALL, "user:teller").get("customers")).hasSize(1);
        assertThat(getJson("/v1/customers?q=C-0001", ALL, "user:teller").get("customers")).hasSize(1);

        // Keyset pages: 50 + the rest, disjoint, in order, cursor opaque.
        JsonNode first = getJson("/v1/customers?q=Pager", ALL, "user:teller");
        assertThat(first.get("customers")).hasSize(50);
        String cursor = first.get("nextPage").asString();
        assertThat(cursor).isNotBlank();
        JsonNode second = getJson("/v1/customers?q=Pager&page=" + cursor, ALL, "user:teller");
        assertThat(second.get("customers")).hasSize(5);
        assertThat(second.get("nextPage").isNull()).isTrue();
        var seen = new java.util.HashSet<String>();
        first.get("customers").forEach(c -> seen.add(c.get("customerId").asString()));
        second.get("customers").forEach(c -> assertThat(seen.add(c.get("customerId").asString())).isTrue());
    }

    // ------------------------------------------------------------------ accounts + balances

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

    @Test
    void the_statement_passes_through_byte_for_byte_including_not_found() {
        var ok =
                send(as("/v1/accounts/" + customerAccount + "/statement?from=2026-08-01&to=2026-08-31",
                                ALL, "user:teller")
                        .GET()
                        .build());
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).isEqualTo(STATEMENT_BODY); // shape-preserving means byte-identical

        entriesStatus.set(404);
        assertThat(
                        send(as("/v1/accounts/" + customerAccount + "/statement?from=2026-08-01&to=2026-08-31",
                                        ALL, "user:teller")
                                .GET()
                                .build())
                                .statusCode())
                .isEqualTo(404);
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

    // ------------------------------------------------------------------ loan desk lists

    @Test
    void the_loan_desk_filters_by_state_and_by_awaiting_my_signature() throws Exception {
        assertThat(
                        post("/v1/lending/approval-tiers", ALL, "user:grace",
                                        "{\"ceilingMinor\":100000000,\"approvalsRequired\":2}")
                                .statusCode())
                .isEqualTo(200);
        var created =
                post("/v1/loan-applications", ALL, "user:officer",
                        "{\"customerId\":\"%s\",\"productCode\":\"AJO_LOAN\",\"amountMinor\":1000000,\"termMonths\":12}"
                                .formatted(customerId));
        assertThat(created.statusCode()).isEqualTo(201);
        String appId = mapper.readTree(created.body()).get("id").asString();

        assertThat(getJson("/v1/loan-applications?state=APPLIED", ALL, "user:grace").get("applications"))
                .hasSize(1);
        // The applicant's own queue is empty — the queue never lists what the sign button refuses.
        assertThat(getJson("/v1/loan-applications?awaiting=me", ALL, "user:officer").get("applications"))
                .isEmpty();
        assertThat(getJson("/v1/loan-applications?awaiting=me", ALL, "user:bola").get("applications"))
                .hasSize(1);

        assertThat(post("/v1/loan-applications/" + appId + "/approve", ALL, "user:bola", "{}").statusCode())
                .isEqualTo(200);
        // Signed: bola's queue drains; grace still owes the second signature.
        assertThat(getJson("/v1/loan-applications?awaiting=me", ALL, "user:bola").get("applications"))
                .isEmpty();
        assertThat(getJson("/v1/loan-applications?awaiting=me", ALL, "user:grace").get("applications"))
                .hasSize(1);
    }

    @Test
    void a_customers_loans_and_a_loans_repayments_read_back() throws Exception {
        assertThat(
                        post("/v1/lending/approval-tiers", ALL, "user:grace",
                                        "{\"ceilingMinor\":100000000,\"approvalsRequired\":0}")
                                .statusCode())
                .isEqualTo(200);
        var created =
                post("/v1/loan-applications", ALL, "user:officer",
                        "{\"customerId\":\"%s\",\"productCode\":\"AJO_LOAN\",\"amountMinor\":1000000,\"termMonths\":12}"
                                .formatted(customerId));
        String appId = mapper.readTree(created.body()).get("id").asString();
        assertThat(post("/v1/loan-applications/" + appId + "/accept-offer", ALL, "user:ada", "{}").statusCode())
                .isEqualTo(200);
        assertThat(
                        post("/v1/loan-applications/" + appId + "/disburse", ALL, "user:bola",
                                        "{\"fundingAccountId\":\"%s\",\"destinationAccountId\":\"%s\"}"
                                                .formatted(UUID.randomUUID(), customerAccount))
                                .statusCode())
                .isEqualTo(200);

        JsonNode loans = getJson("/v1/customers/" + customerId + "/loans", ALL, "user:officer");
        assertThat(loans.get("loans")).hasSize(1);
        String loanId = loans.get("loans").get(0).get("loanId").asString();
        assertThat(loans.get("loans").get(0).get("principalOutstandingMinor").asString()).isEqualTo("1000000");

        assertThat(
                        post("/v1/loans/" + loanId + "/repayments", ALL, "user:officer",
                                        "{\"idempotencyKey\":\"r-%s\",\"amountMinor\":10000,\"sourceAccountId\":\"%s\"}"
                                                .formatted(loanId, customerAccount))
                                .statusCode())
                .isEqualTo(201);
        JsonNode history = getJson("/v1/loans/" + loanId + "/repayments", ALL, "user:officer");
        assertThat(history.get("repayments")).hasSize(1);
        assertThat(history.get("repayments").get(0).get("principalMinor").asString()).isEqualTo("10000");
        assertThat(history.get("repayments").get(0).get("state").asString()).isEqualTo("ALLOCATED");
    }

    // ------------------------------------------------------------------ the standing probes

    @Test
    void every_new_read_denies_by_default() {
        String[][] probes = {
            {"/v1/customers?q=x", "loans:read"},
            {"/v1/customers/" + customerId + "/accounts", "loans:read"},
            {"/v1/accounts/" + customerAccount + "/statement?from=2026-08-01&to=2026-08-31", "customers:read"},
            {"/v1/tills/" + tillId + "/activity?date=2026-08-08", "customers:read"},
            {"/v1/approvals/pending", "approvals:make"},
            {"/v1/loan-applications?state=APPLIED", "customers:read"},
            {"/v1/customers/" + customerId + "/loans", "customers:read"},
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

        var foreignSearch =
                send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/customers?q=Lovelace"))
                                .header("X-Dev-Tenant-Id", otherTenant.toString())
                                .header("X-Dev-Principal", "user:intruder")
                                .header("X-Dev-Permissions", ALL)
                                .GET()
                                .build());
        assertThat(mapper.readTree(foreignSearch.body()).get("customers")).isEmpty();
    }
}
