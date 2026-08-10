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
import java.util.concurrent.atomic.AtomicInteger;
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
 * The lending lifecycle over HTTP (lending.md §4–§5): the chain's rules, the one edge where money
 * moves, allocation on completion, and the segment property the design stakes — zero-approval
 * tiers, one-approval books and committees are the same code path configured differently.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LendingApiTest {

    private static final String ALL =
            "loans:apply,loans:read,loans:approve,loans:offer,loans:disburse,loans:repay,loans:portfolio,loans:tiers";

    @Autowired private TenantRegistry tenantRegistry;
    @LocalServerPort private int port;
    @Autowired @Qualifier("customerJdbcTemplate") private JdbcTemplate customerDb;
    @Autowired @Qualifier("productJdbcTemplate") private JdbcTemplate productDb;
    @Autowired @Qualifier("customerTransactionManager") private PlatformTransactionManager customerTx;
    @Autowired @Qualifier("productTransactionManager") private PlatformTransactionManager productTx;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper mapper = JsonMapper.builder().build();

    private static HttpServer ledger;
    private static final AtomicInteger postStatus = new AtomicInteger(201);

    private UUID tenantId;
    private UUID customerId;
    private UUID customerAccount;
    private UUID incomeAccount;
    private UUID configuredFunding;

    @org.junit.jupiter.api.BeforeAll
    static void startLedger() throws IOException {
        ledger = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ledger.createContext(
                "/",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    int status = "POST".equals(exchange.getRequestMethod()) ? postStatus.get() : 503;
                    byte[] bytes =
                            (status == 201
                                            ? "{\"transactionId\":\"" + UUID.randomUUID() + "\"}"
                                            : "{\"code\":\"INSUFFICIENT_FUNDS\"}")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        ledger.start();
    }

    @org.junit.jupiter.api.AfterAll
    static void stopLedger() {
        ledger.stop(0);
    }

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) {
        registry.add("fincore.core.ledger.base-url", () -> "http://127.0.0.1:" + ledger.getAddress().getPort());
        registry.add("fincore.core.worker.interval-ms", () -> "3600000");
        registry.add("fincore.core.outbox.relay.interval-ms", () -> "3600000");
        registry.add("fincore.core.lending.jobs.interval-ms", () -> "3600000");
        registry.add("fincore.test.context", () -> "lending-api");
    }

    @BeforeEach
    void seed() {
        tenantId = UUID.randomUUID();
        tenantRegistry.register(tenantId, "test tenant", "test");
        customerId = UUID.randomUUID();
        customerAccount = UUID.randomUUID();
        incomeAccount = UUID.randomUUID();
        configuredFunding = UUID.randomUUID();
        postStatus.set(201);

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
                                                    + " VALUES (?, 'AJO_LOAN', 'Ajo Loan', 'LOAN') RETURNING id",
                                            UUID.class, tenantId);
                            UUID versionId =
                                    productDb.queryForObject(
                                            "INSERT INTO product.product_versions (tenant_id, product_id, version,"
                                                    + " status, created_by, published_by)"
                                                    + " VALUES (?,?,1,'DRAFT','user:author',NULL) RETURNING id",
                                            UUID.class, tenantId, productId);
                            productDb.update(
                                    """
                                    INSERT INTO product.loan_rules
                                        (tenant_id, product_version_id, interest_rate_bp, schedule_kind,
                                         min_amount_minor, max_amount_minor, min_term_months, max_term_months,
                                         currency, interest_income_account_id, funding_account_id)
                                    VALUES (?,?, 2400, 'FLAT', 10000, 100000000, 1, 36, 'NGN', ?, ?)
                                    """,
                                    tenantId, versionId, incomeAccount, configuredFunding);
                            // Published last, because pricing for a live version is immutable (V7):
                            // a rule added after publish would change what an already-decided transaction
                            // was priced under, and the database refuses it.
                            productDb.update(
                                    "UPDATE product.product_versions SET status = 'PUBLISHED',"
                                            + " published_by = 'user:publisher' WHERE tenant_id = ? AND id = ?",
                                    tenantId, versionId);
                        });
    }

    // ---------------------------------------------------------------- helpers

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

    private HttpResponse<String> post(String path, String permissions, String principal, String body) {
        return send(as(path, permissions, principal).POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private void setTier(long ceiling, int approvals) {
        assertThat(
                        post("/v1/lending/approval-tiers", ALL, "user:grace",
                                        "{\"ceilingMinor\":%d,\"approvalsRequired\":%d}".formatted(ceiling, approvals))
                                .statusCode())
                .isEqualTo(200);
    }

    private JsonNode apply(long amount, String principal) throws Exception {
        HttpResponse<String> created =
                post("/v1/loan-applications", ALL, principal,
                        "{\"customerId\":\"%s\",\"productCode\":\"AJO_LOAN\",\"amountMinor\":%d,\"termMonths\":12,\"purpose\":\"stock\"}"
                                .formatted(customerId, amount));
        assertThat(created.statusCode()).isEqualTo(201);
        return mapper.readTree(created.body());
    }

    private JsonNode disburseToActive(String appId) throws Exception {
        HttpResponse<String> accepted =
                post("/v1/loan-applications/" + appId + "/accept-offer", ALL, "user:ada", "{}");
        assertThat(accepted.statusCode()).isEqualTo(200);
        HttpResponse<String> disbursed =
                post("/v1/loan-applications/" + appId + "/disburse", ALL, "user:bola",
                        "{\"fundingAccountId\":\"%s\",\"destinationAccountId\":\"%s\"}"
                                .formatted(UUID.randomUUID(), customerAccount));
        assertThat(disbursed.statusCode()).isEqualTo(200);
        JsonNode app = mapper.readTree(disbursed.body());
        assertThat(app.get("state").asString()).isEqualTo("ACTIVE");
        return app;
    }

    // ---------------------------------------------------------------- the spectrum

    @Test
    void a_zero_tier_auto_approves_attributed_to_the_policy() throws Exception {
        setTier(1_000_000, 0);
        JsonNode app = apply(500_000, "user:fintech-api");

        // Instantly offered — and the approval is recorded, attributed to the policy.
        assertThat(app.get("state").asString()).isEqualTo("OFFERED");
        assertThat(app.get("approvals").asInt()).isEqualTo(1);
        assertThat(app.get("offerTotalCostMinor").asLong()).isGreaterThan(500_000);
    }

    @Test
    void above_the_zero_ceiling_a_human_chain_takes_over() throws Exception {
        setTier(1_000_000, 0);
        setTier(50_000_000, 2);
        JsonNode app = apply(5_000_000, "user:officer");
        assertThat(app.get("state").asString()).isEqualTo("APPLIED");
        String id = app.get("id").asString();

        // The applicant may not sign their own application.
        HttpResponse<String> self = post("/v1/loan-applications/" + id + "/approve", ALL, "user:officer", "{}");
        assertThat(self.statusCode()).isEqualTo(422);
        assertThat(self.body()).contains("APPROVAL_SEQUENCE_INVALID");

        // First signature: still APPLIED — the tier wants two.
        assertThat(mapper.readTree(post("/v1/loan-applications/" + id + "/approve", ALL, "user:bola", "{}").body())
                        .get("state").asString())
                .isEqualTo("APPLIED");

        // The same signer twice is refused by the unique index.
        assertThat(post("/v1/loan-applications/" + id + "/approve", ALL, "user:bola", "{}").statusCode())
                .isEqualTo(422);

        // The second distinct signature completes the chain and generates the offer.
        JsonNode offered =
                mapper.readTree(post("/v1/loan-applications/" + id + "/approve", ALL, "user:grace", "{}").body());
        assertThat(offered.get("state").asString()).isEqualTo("OFFERED");
        assertThat(offered.get("approvals").asInt()).isEqualTo(2);
    }

    @Test
    void an_unconfigured_tenant_degrades_to_one_human_never_to_auto_approval() throws Exception {
        JsonNode app = apply(500_000, "user:officer");
        assertThat(app.get("state").asString()).isEqualTo("APPLIED");
        assertThat(app.get("approvalsRequired").asInt()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- money and closure

    @Test
    void the_full_lifecycle_disburses_repays_and_closes() throws Exception {
        setTier(100_000_000, 0);
        JsonNode app = apply(1_000_000, "user:officer");
        String appId = app.get("id").asString();
        disburseToActive(appId);

        // The portfolio lists exactly one ACTIVE loan for this tenant, current.
        JsonNode par =
                mapper.readTree(send(as("/v1/portfolio/par", ALL, "user:grace").GET().build()).body());
        assertThat(par).hasSize(1);
        assertThat(par.get(0).get("bucket").asString()).isEqualTo("CURRENT");

        // Repay the full payoff (no accrual has run: payoff == principal) and the loan closes.
        UUID loanId = UUID.fromString(findLoanId(appId));
        HttpResponse<String> repaid =
                post("/v1/loans/" + loanId + "/repayments", ALL, "user:officer",
                        "{\"idempotencyKey\":\"rep-%s\",\"amountMinor\":1000000,\"sourceAccountId\":\"%s\"}"
                                .formatted(loanId, customerAccount));
        assertThat(repaid.statusCode()).isEqualTo(201);
        assertThat(mapper.readTree(repaid.body()).get("state").asString()).isEqualTo("ALLOCATED");

        JsonNode loan = mapper.readTree(send(as("/v1/loans/" + loanId, ALL, "user:officer").GET().build()).body());
        assertThat(loan.get("state").asString()).isEqualTo("CLOSED");
        assertThat(loan.get("principalOutstandingMinor").asString()).isEqualTo("0");

        // The schedule's principal is fully paid down — plan interest was never owed on a
        // day-zero payoff; the loan's actual interest lives in accrual, which is zero here.
        JsonNode schedule =
                mapper.readTree(send(as("/v1/loans/" + loanId + "/schedule", ALL, "user:officer").GET().build()).body());
        assertThat(schedule).hasSize(12);
        long principalPaid = 0;
        for (JsonNode row : schedule) {
            principalPaid += Long.parseLong(row.get("principalPaidMinor").asString());
        }
        assertThat(principalPaid).isEqualTo(1_000_000L);
    }

    @Test
    void a_repayment_beyond_payoff_is_refused_at_intake() throws Exception {
        setTier(100_000_000, 0);
        JsonNode app = apply(1_000_000, "user:officer");
        disburseToActive(app.get("id").asString());
        UUID loanId = UUID.fromString(findLoanId(app.get("id").asString()));

        HttpResponse<String> over =
                post("/v1/loans/" + loanId + "/repayments", ALL, "user:officer",
                        "{\"idempotencyKey\":\"over-%s\",\"amountMinor\":999999999,\"sourceAccountId\":\"%s\"}"
                                .formatted(loanId, customerAccount));
        assertThat(over.statusCode()).isEqualTo(422);
        assertThat(over.body()).contains("REPAYMENT_EXCEEDS_PAYOFF");
    }

    @Test
    void a_refused_disbursement_returns_the_application_to_accepted() throws Exception {
        setTier(100_000_000, 0);
        JsonNode app = apply(1_000_000, "user:officer");
        String appId = app.get("id").asString();
        assertThat(post("/v1/loan-applications/" + appId + "/accept-offer", ALL, "user:ada", "{}").statusCode())
                .isEqualTo(200);

        postStatus.set(422);
        HttpResponse<String> refused =
                post("/v1/loan-applications/" + appId + "/disburse", ALL, "user:bola",
                        "{\"fundingAccountId\":\"%s\",\"destinationAccountId\":\"%s\"}"
                                .formatted(UUID.randomUUID(), customerAccount));
        assertThat(refused.statusCode()).isEqualTo(422);

        JsonNode after =
                mapper.readTree(send(as("/v1/loan-applications/" + appId, ALL, "user:officer").GET().build()).body());
        assertThat(after.get("state").asString()).isEqualTo("ACCEPTED");
    }

    // ---------------------------------------------------------------- v1.17: penalties, recognition, funding config

    @Test
    void the_configured_funding_account_overrides_the_callers() throws Exception {
        setTier(100_000_000, 0);
        JsonNode app = apply(1_000_000, "user:officer");
        disburseToActive(app.get("id").asString()); // disburseToActive supplies a random account

        UUID recorded =
                workerDb.queryForObject(
                        "SELECT funding_account_id FROM lending.loans WHERE application_id = ?::uuid",
                        UUID.class, app.get("id").asString());
        assertThat(recorded).isEqualTo(configuredFunding);
    }

    @Test
    void collected_interest_is_recognized_under_the_repayments_derived_key_and_replays_converge()
            throws Exception {
        setTier(100_000_000, 0);
        JsonNode app = apply(1_000_000, "user:officer");
        disburseToActive(app.get("id").asString());
        UUID loanId = UUID.fromString(findLoanId(app.get("id").asString()));
        workerDb.update(
                "UPDATE lending.loans SET accrued_interest_minor = 10000 WHERE id = ?", loanId);

        JsonNode repaid =
                mapper.readTree(
                        post("/v1/loans/" + loanId + "/repayments", ALL, "user:officer",
                                        "{\"idempotencyKey\":\"int-%s\",\"amountMinor\":10000,\"sourceAccountId\":\"%s\"}"
                                                .formatted(loanId, customerAccount))
                                .body());
        String repaymentId = repaid.get("repaymentId").asString();

        // The income posting exists as a RECOGNITION saga under the per-repayment derived key,
        // from the loan's funding account to the product's income account.
        assertThat(
                        workerDb.queryForObject(
                                "SELECT count(*) FROM orchestration.sagas WHERE type = 'RECOGNITION'"
                                        + " AND channel_idempotency_key = ?",
                                Long.class, "lending:recognize:" + repaymentId + ":interest"))
                .isEqualTo(1L);
        assertThat(
                        workerDb.queryForObject(
                                "SELECT to_account_id FROM orchestration.sagas WHERE channel_idempotency_key = ?",
                                UUID.class, "lending:recognize:" + repaymentId + ":interest"))
                .isEqualTo(incomeAccount);
        assertThat(
                        workerDb.queryForObject(
                                "SELECT recognized_interest_minor FROM lending.loans WHERE id = ?", Long.class, loanId))
                .isEqualTo(10_000L);
        assertThat(
                        workerDb.queryForObject(
                                "SELECT recognized_at IS NOT NULL FROM lending.repayments WHERE id = ?::uuid",
                                Boolean.class, repaymentId))
                .isTrue();

        // The catch-up pass finds nothing to redo; the derived key means nothing double-posts.
        jobs.recognize();
        assertThat(
                        workerDb.queryForObject(
                                "SELECT count(*) FROM orchestration.sagas WHERE channel_idempotency_key = ?",
                                Long.class, "lending:recognize:" + repaymentId + ":interest"))
                .isEqualTo(1L);
        assertThat(
                        workerDb.queryForObject(
                                "SELECT recognized_interest_minor FROM lending.loans WHERE id = ?", Long.class, loanId))
                .isEqualTo(10_000L);
    }

    @Test
    void penalties_allocate_first_count_in_payoff_and_gate_closure() throws Exception {
        setTier(100_000_000, 0);
        JsonNode app = apply(1_000_000, "user:officer");
        disburseToActive(app.get("id").asString());
        UUID loanId = UUID.fromString(findLoanId(app.get("id").asString()));
        workerDb.update("UPDATE lending.loans SET penalty_charged_minor = 50000 WHERE id = ?", loanId);

        // The view carries the due, and payoff includes it.
        JsonNode view = mapper.readTree(send(as("/v1/loans/" + loanId, ALL, "user:officer").GET().build()).body());
        assertThat(view.get("penaltyDueMinor").asString()).isEqualTo("50000");
        assertThat(view.get("payoffMinor").asString()).isEqualTo("1050000");

        // A kobo beyond payoff-with-penalties is refused at intake.
        assertThat(
                        post("/v1/loans/" + loanId + "/repayments", ALL, "user:officer",
                                        "{\"idempotencyKey\":\"o-%s\",\"amountMinor\":1050001,\"sourceAccountId\":\"%s\"}"
                                                .formatted(loanId, customerAccount))
                                .statusCode())
                .isEqualTo(422);

        // A partial payment reaches PENALTY first (the default order), and the penalty portion
        // is recognized to the fallback income account under the :penalty derived key.
        JsonNode part =
                mapper.readTree(
                        post("/v1/loans/" + loanId + "/repayments", ALL, "user:officer",
                                        "{\"idempotencyKey\":\"p-%s\",\"amountMinor\":30000,\"sourceAccountId\":\"%s\"}"
                                                .formatted(loanId, customerAccount))
                                .body());
        assertThat(
                        workerDb.queryForObject(
                                "SELECT SUM(amount_minor) FROM lending.repayment_allocations"
                                        + " WHERE repayment_id = ?::uuid AND component = 'PENALTY'",
                                Long.class, part.get("repaymentId").asString()))
                .isEqualTo(30_000L);
        assertThat(
                        workerDb.queryForObject(
                                "SELECT count(*) FROM orchestration.sagas WHERE type = 'RECOGNITION'"
                                        + " AND channel_idempotency_key = ?",
                                Long.class,
                                "lending:recognize:" + part.get("repaymentId").asString() + ":penalty"))
                .isEqualTo(1L);
        assertThat(
                        mapper.readTree(send(as("/v1/loans/" + loanId, ALL, "user:officer").GET().build()).body())
                                .get("penaltyDueMinor").asString())
                .isEqualTo("20000");

        // Settling the rest — remaining penalty plus principal — closes the loan.
        assertThat(
                        post("/v1/loans/" + loanId + "/repayments", ALL, "user:officer",
                                        "{\"idempotencyKey\":\"f-%s\",\"amountMinor\":1020000,\"sourceAccountId\":\"%s\"}"
                                                .formatted(loanId, customerAccount))
                                .statusCode())
                .isEqualTo(201);
        assertThat(
                        mapper.readTree(send(as("/v1/loans/" + loanId, ALL, "user:officer").GET().build()).body())
                                .get("state").asString())
                .isEqualTo("CLOSED");
    }

    @Test
    void an_unconfigured_income_account_resolves_recognition_as_a_recorded_noop() throws Exception {
        new TransactionTemplate(productTx)
                .executeWithoutResult(
                        s -> {
                            productDb.queryForObject(
                                    "SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
                            // A published version's pricing is immutable (V7), which is the
                            // platform's rule and not an obstacle to work around: an institution
                            // whose loan product has no income account got there by publishing it
                            // that way. Reproduced faithfully — draft the next version without the
                            // account, publish it, and let the money path resolve the live one.
                            UUID productId =
                                    productDb.queryForObject(
                                            "SELECT id FROM product.products WHERE tenant_id = ? AND code = 'AJO_LOAN'",
                                            UUID.class, tenantId);
                            UUID next =
                                    productDb.queryForObject(
                                            "INSERT INTO product.product_versions (tenant_id, product_id, version,"
                                                    + " status, created_by)"
                                                    + " VALUES (?,?,2,'DRAFT','user:author') RETURNING id",
                                            UUID.class, tenantId, productId);
                            productDb.update(
                                    "INSERT INTO product.loan_rules (tenant_id, product_version_id,"
                                            + " interest_rate_bp, schedule_kind, min_amount_minor,"
                                            + " max_amount_minor, min_term_months, max_term_months,"
                                            + " allocation_order, currency, funding_account_id)"
                                            + " SELECT tenant_id, ?, interest_rate_bp, schedule_kind,"
                                            + "        min_amount_minor, max_amount_minor, min_term_months,"
                                            + "        max_term_months, allocation_order, currency,"
                                            + "        funding_account_id"
                                            + "   FROM product.loan_rules WHERE tenant_id = ?"
                                            + "  ORDER BY id LIMIT 1",
                                    next, tenantId);
                            productDb.update(
                                    "UPDATE product.product_versions SET status = 'PUBLISHED',"
                                            + " published_by = 'user:publisher' WHERE tenant_id = ? AND id = ?",
                                    tenantId, next);
                        });
        setTier(100_000_000, 0);
        JsonNode app = apply(1_000_000, "user:officer");
        disburseToActive(app.get("id").asString());
        UUID loanId = UUID.fromString(findLoanId(app.get("id").asString()));
        workerDb.update("UPDATE lending.loans SET accrued_interest_minor = 5000 WHERE id = ?", loanId);

        JsonNode repaid =
                mapper.readTree(
                        post("/v1/loans/" + loanId + "/repayments", ALL, "user:officer",
                                        "{\"idempotencyKey\":\"n-%s\",\"amountMinor\":5000,\"sourceAccountId\":\"%s\"}"
                                                .formatted(loanId, customerAccount))
                                .body());

        // Resolved as an explicit no-op: marked, nothing posted, nothing pending for the gauge.
        assertThat(
                        workerDb.queryForObject(
                                "SELECT recognized_at IS NOT NULL FROM lending.repayments WHERE id = ?::uuid",
                                Boolean.class, repaid.get("repaymentId").asString()))
                .isTrue();
        assertThat(
                        workerDb.queryForObject(
                                "SELECT recognized_interest_minor FROM lending.loans WHERE id = ?", Long.class, loanId))
                .isZero();
        assertThat(
                        workerDb.queryForObject(
                                "SELECT count(*) FROM orchestration.sagas WHERE channel_idempotency_key LIKE ?",
                                Long.class, "lending:recognize:" + repaid.get("repaymentId").asString() + "%"))
                .isZero();
    }

    // ---------------------------------------------------------------- guards

    @Test
    void bounds_and_product_type_are_product_facts() throws Exception {
        HttpResponse<String> tiny =
                post("/v1/loan-applications", ALL, "user:officer",
                        "{\"customerId\":\"%s\",\"productCode\":\"AJO_LOAN\",\"amountMinor\":1,\"termMonths\":12}"
                                .formatted(customerId));
        assertThat(tiny.statusCode()).isEqualTo(422);
        assertThat(tiny.body()).contains("AMOUNT_OUT_OF_BOUNDS");

        HttpResponse<String> unknown =
                post("/v1/loan-applications", ALL, "user:officer",
                        "{\"customerId\":\"%s\",\"productCode\":\"NOT_A_LOAN\",\"amountMinor\":50000,\"termMonths\":12}"
                                .formatted(customerId));
        assertThat(unknown.statusCode()).isEqualTo(422);
        assertThat(unknown.body()).contains("PRODUCT_NOT_LENDABLE");
    }

    @Test
    void the_surface_denies_by_default_and_hides_other_tenants() throws Exception {
        assertThat(post("/v1/loan-applications", "transfers:create", "user:x",
                                "{\"customerId\":\"%s\",\"productCode\":\"AJO_LOAN\",\"amountMinor\":50000,\"termMonths\":12}"
                                        .formatted(customerId))
                        .statusCode())
                .isEqualTo(403);

        setTier(100_000_000, 0);
        JsonNode app = apply(1_000_000, "user:officer");

        UUID otherTenant = UUID.randomUUID();
        tenantRegistry.register(otherTenant, "other", "test");
        HttpResponse<String> foreign =
                send(HttpRequest.newBuilder(
                                        URI.create("http://localhost:" + port + "/v1/loan-applications/"
                                                + app.get("id").asString()))
                                .header("X-Dev-Tenant-Id", otherTenant.toString())
                                .header("X-Dev-Principal", "user:intruder")
                                .header("X-Dev-Permissions", ALL)
                                .GET()
                                .build());
        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(foreign.body()).contains("LOAN_NOT_FOUND");
    }

    // ---------------------------------------------------------------- lookup

    @Autowired private org.elyonar.fincore.core.lending.internal.LoanRecords lendingRecords;
    @Autowired private org.elyonar.fincore.core.lending.internal.LendingJobs jobs;
    @Autowired @Qualifier("workerJdbcTemplate") private JdbcTemplate workerDb;
    @Autowired @Qualifier("lendingJdbcTemplate") private JdbcTemplate lendingDb;
    @Autowired @Qualifier("lendingTransactionManager") private PlatformTransactionManager lendingTx;

    private String findLoanId(String applicationId) {
        return new TransactionTemplate(lendingTx)
                .execute(
                        s -> {
                            lendingDb.queryForObject(
                                    "SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
                            return lendingDb.queryForObject(
                                    "SELECT id::text FROM lending.loans WHERE application_id = ?::uuid",
                                    String.class,
                                    applicationId);
                        });
    }
}
