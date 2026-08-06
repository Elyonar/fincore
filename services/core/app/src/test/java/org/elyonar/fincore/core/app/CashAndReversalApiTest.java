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
import java.util.concurrent.atomic.AtomicReference;
import org.elyonar.fincore.core.orchestration.internal.saga.TillRecords;
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

/**
 * The three endpoints that existed as logic and not as routes.
 *
 * <p>{@code CashService} and {@code ReversalService} have been built and tested since Core's first
 * slice, and the CHANGELOG listed deposit, withdrawal and business reversal as in scope for v1. All
 * three were reachable only from a test. That is the specific kind of incompleteness worth a test
 * of its own, because a service with passing tests and no route looks finished from every angle
 * except the one a caller stands in.
 *
 * <p>{@code a_reversal_needs_an_approval_someone_else_checked} walks the whole maker-checker path
 * over HTTP — raise, check as a different principal, then spend it — because that is the sequence
 * an operator actually performs, and the parts were only ever proven separately.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CashAndReversalApiTest {

    private static HttpServer ledger;
    private static final AtomicInteger ledgerStatus = new AtomicInteger(201);
    private static final AtomicReference<String> ledgerBody = new AtomicReference<>("{}");
    private static final AtomicReference<String> lastRequest = new AtomicReference<>();

    @LocalServerPort private int port;
    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired private TillRecords tills;
    @Autowired @Qualifier("customerJdbcTemplate") private JdbcTemplate customerDb;
    @Autowired @Qualifier("productJdbcTemplate") private JdbcTemplate productDb;
    @Autowired @Qualifier("customerTransactionManager") private PlatformTransactionManager customerTx;
    @Autowired @Qualifier("productTransactionManager") private PlatformTransactionManager productTx;

    private UUID tenantId;
    private UUID customerId;
    private UUID customerAccount;
    private UUID tillAccount;
    private UUID tillId;
    private UUID feeAccount;
    private UUID counterparty;

    @BeforeAll
    static void startLedger() throws IOException {
        ledger = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ledger.createContext(
                "/",
                exchange -> {
                    lastRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
    static void config(DynamicPropertyRegistry registry) {
        registry.add("fincore.core.ledger.base-url", () -> "http://127.0.0.1:" + ledger.getAddress().getPort());
        registry.add("fincore.core.worker.interval-ms", () -> "3600000");
        registry.add("fincore.core.outbox.relay.interval-ms", () -> "3600000");
    }

    @BeforeEach
    void seed() {
        tenantId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        customerAccount = UUID.randomUUID();
        tillAccount = UUID.randomUUID();
        feeAccount = UUID.randomUUID();
        counterparty = UUID.randomUUID();
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
                                                    + " VALUES (?,?,1,'PUBLISHED','user:author','user:publisher')"
                                                    + " RETURNING id",
                                            UUID.class, tenantId, productId);
                            for (String channel : new String[] {"TELLER", "API"}) {
                                productDb.update(
                                        "INSERT INTO product.limit_rules (tenant_id, product_version_id, kyc_tier,"
                                                + " channel, limit_type, max_amount_minor, currency)"
                                                + " VALUES (?,?, 'TIER_2', ?, 'PER_TXN', 5000000, 'NGN')",
                                        tenantId, versionId, channel);
                            }
                            for (String operation : new String[] {"DEPOSIT", "WITHDRAWAL", "TRANSFER"}) {
                                productDb.update(
                                        "INSERT INTO product.fee_rules (tenant_id, product_version_id, operation,"
                                                + " kind, flat_minor, currency) VALUES (?,?,?, 'FLAT', 5000, 'NGN')",
                                        tenantId, versionId, operation);
                            }
                        });

        tillId = tills.open(tenantId, "BR-01", tillAccount, "NGN", "user:teller-1");
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

    private static String field(String json, String name) {
        int at = json.indexOf("\"" + name + "\":\"");
        if (at < 0) {
            return null;
        }
        at += name.length() + 4;
        return json.substring(at, json.indexOf('"', at));
    }

    private String cashBody(String key, long amountMinor) {
        return ("{\"idempotencyKey\":\"%s\",\"customerId\":\"%s\",\"customerAccountId\":\"%s\","
                        + "\"tillId\":\"%s\",\"feeAccountId\":\"%s\",\"amountMinor\":%d,"
                        + "\"currency\":\"NGN\",\"productCode\":\"P\",\"channel\":\"TELLER\","
                        + "\"description\":\"counter\"}")
                .formatted(key, customerId, customerAccount, tillId, feeAccount, amountMinor);
    }

    private HttpResponse<String> cash(String path, String key, long amountMinor) {
        return send(
                as(path, "cash:transact", "user:teller-1")
                        .POST(HttpRequest.BodyPublishers.ofString(cashBody(key, amountMinor)))
                        .build());
    }

    // ------------------------------------------------------------------ deposits

    @Test
    void a_deposit_over_http_debits_the_till_and_credits_the_customer() {
        HttpResponse<String> response = cash("/v1/deposits", "dep-" + UUID.randomUUID(), 100_000);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("COMPLETED");
        // The direction is the thing that must never be wrong: a withdrawal recorded as a deposit
        // balances perfectly and empties a till.
        assertThat(lastRequest.get())
                .contains("\"accountId\":\"" + tillAccount + "\"")
                .contains("\"direction\":\"DEBIT\"");
    }

    @Test
    void a_withdrawal_over_http_is_the_mirror_of_a_deposit() {
        cash("/v1/deposits", "dep-" + UUID.randomUUID(), 200_000);
        String afterDeposit = lastRequest.get();

        HttpResponse<String> response = cash("/v1/withdrawals", "wd-" + UUID.randomUUID(), 100_000);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("COMPLETED");
        assertThat(lastRequest.get()).isNotEqualTo(afterDeposit);
        assertThat(lastRequest.get())
                .contains("\"accountId\":\"" + tillAccount + "\"")
                .contains("\"direction\":\"CREDIT\"");
    }

    @Test
    void replaying_a_cash_key_returns_the_first_answer_rather_than_moving_money_twice() {
        String key = "dep-" + UUID.randomUUID();

        HttpResponse<String> first = cash("/v1/deposits", key, 100_000);
        HttpResponse<String> second = cash("/v1/deposits", key, 100_000);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(201);
        assertThat(field(second.body(), "transactionId")).isEqualTo(field(first.body(), "transactionId"));
    }

    @Test
    void the_same_key_used_for_a_deposit_and_a_withdrawal_is_a_caller_bug() {
        String key = "shared-" + UUID.randomUUID();
        assertThat(cash("/v1/deposits", key, 100_000).statusCode()).isEqualTo(201);

        HttpResponse<String> crossed = cash("/v1/withdrawals", key, 100_000);

        // The operation is part of the fingerprint, so this surfaces as a conflict rather than
        // replaying the deposit's answer to a request that asked for the opposite direction.
        assertThat(crossed.statusCode()).isEqualTo(409);
        assertThat(crossed.body()).contains("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void cash_endpoints_deny_by_default() {
        String body = cashBody("k-" + UUID.randomUUID(), 100_000);
        for (String path : new String[] {"/v1/deposits", "/v1/withdrawals"}) {
            assertThat(
                            send(
                                            as(path, "transfers:create", "user:teller-1")
                                                    .POST(HttpRequest.BodyPublishers.ofString(body))
                                                    .build())
                                    .statusCode())
                    .as(path)
                    .isEqualTo(403);
        }
    }

    // ------------------------------------------------------------------ reversal

    private String completedTransfer(long amountMinor) {
        String body =
                ("{\"idempotencyKey\":\"tr-%s\",\"customerId\":\"%s\",\"fromAccountId\":\"%s\","
                                + "\"toAccountId\":\"%s\",\"feeAccountId\":\"%s\",\"amountMinor\":%d,"
                                + "\"currency\":\"NGN\",\"productCode\":\"P\",\"channel\":\"API\"}")
                        .formatted(UUID.randomUUID(), customerId, customerAccount, counterparty, feeAccount, amountMinor);
        HttpResponse<String> posted =
                send(
                        as("/v1/transfers", "transfers:create", "user:teller-1")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build());
        assertThat(posted.statusCode()).isEqualTo(201);
        return field(posted.body(), "transactionId");
    }

    /** Raises an approval and has a different principal check it, entirely over HTTP. */
    private String approvalFor(String transactionId, long amountMinor) {
        HttpResponse<String> raised =
                send(
                        as("/v1/approvals", "approvals:make", "user:maker")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"targetTransactionId\":\"" + transactionId
                                                        + "\",\"amountMinor\":" + amountMinor + "}"))
                                .build());
        assertThat(raised.statusCode()).isEqualTo(201);
        String approvalId = field(raised.body(), "approvalId");

        HttpResponse<String> checked =
                send(
                        as("/v1/approvals/" + approvalId + "/check", "approvals:check", "user:checker")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"approved\":true}"))
                                .build());
        assertThat(checked.statusCode()).isEqualTo(200);
        return approvalId;
    }

    @Test
    void a_reversal_needs_an_approval_someone_else_checked() {
        String transactionId = completedTransfer(100_000);
        String approvalId = approvalFor(transactionId, 100_000);

        HttpResponse<String> reversed =
                send(
                        as("/v1/transactions/" + transactionId + "/reverse", "transfers:reverse", "user:ops")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"idempotencyKey\":\"rev-" + UUID.randomUUID()
                                                        + "\",\"approvalId\":\"" + approvalId + "\"}"))
                                .build());

        assertThat(reversed.statusCode()).isEqualTo(201);
        assertThat(reversed.body()).contains("COMPLETED");
    }

    @Test
    void a_transaction_cannot_be_reversed_twice_even_with_a_fresh_approval() {
        String transactionId = completedTransfer(100_000);
        String reverseUrl = "/v1/transactions/" + transactionId + "/reverse";

        assertThat(
                        send(
                                        as(reverseUrl, "transfers:reverse", "user:ops")
                                                .POST(
                                                        HttpRequest.BodyPublishers.ofString(
                                                                "{\"idempotencyKey\":\"rev-" + UUID.randomUUID()
                                                                        + "\",\"approvalId\":\""
                                                                        + approvalFor(transactionId, 100_000) + "\"}"))
                                                .build())
                                .statusCode())
                .isEqualTo(201);

        // A new key and a second, freshly checked approval — so this is a genuine second attempt
        // with valid authority, not a replay and not an exhausted signature.
        HttpResponse<String> again =
                send(
                        as(reverseUrl, "transfers:reverse", "user:ops")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"idempotencyKey\":\"rev-" + UUID.randomUUID()
                                                        + "\",\"approvalId\":\""
                                                        + approvalFor(transactionId, 100_000) + "\"}"))
                                .build());

        // NOT_REVERSIBLE, not APPROVAL_INVALID: the already-reversed check runs before the approval
        // is examined. That ordering is the stronger one and worth pinning down — it means a
        // transaction cannot be reversed twice no matter how much authority is produced, so getting
        // signatures is never a route around it.
        assertThat(again.statusCode()).isEqualTo(422);
        assertThat(again.body()).contains("NOT_REVERSIBLE");
    }

    @Test
    void an_approval_raised_for_a_different_amount_does_not_authorise_this_reversal() {
        String transactionId = completedTransfer(100_000);
        String approvalId = approvalFor(transactionId, 999_999);

        HttpResponse<String> reversed =
                send(
                        as("/v1/transactions/" + transactionId + "/reverse", "transfers:reverse", "user:ops")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"idempotencyKey\":\"rev-" + UUID.randomUUID()
                                                        + "\",\"approvalId\":\"" + approvalId + "\"}"))
                                .build());

        // An approval is bound to one target and one amount. Otherwise a signature for ₦1,000
        // reverses ₦1,000,000.
        assertThat(reversed.statusCode()).isEqualTo(403);
    }

    @Test
    void a_transaction_that_was_never_completed_cannot_be_reversed() {
        HttpResponse<String> reversed =
                send(
                        as("/v1/transactions/" + UUID.randomUUID() + "/reverse", "transfers:reverse", "user:ops")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"idempotencyKey\":\"rev-" + UUID.randomUUID()
                                                        + "\",\"approvalId\":\"" + UUID.randomUUID() + "\"}"))
                                .build());

        assertThat(reversed.statusCode()).isEqualTo(422);
        assertThat(reversed.body()).contains("NOT_REVERSIBLE");
    }

    @Test
    void reversal_denies_by_default() {
        String transactionId = completedTransfer(100_000);

        assertThat(
                        send(
                                        as("/v1/transactions/" + transactionId + "/reverse", "transfers:create", "user:x")
                                                .POST(
                                                        HttpRequest.BodyPublishers.ofString(
                                                                "{\"idempotencyKey\":\"k\",\"approvalId\":\""
                                                                        + UUID.randomUUID() + "\"}"))
                                                .build())
                                .statusCode())
                .isEqualTo(403);
    }
}
