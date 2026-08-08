package org.elyonar.fincore.core.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.elyonar.fincore.core.orchestration.api.TransferCommand;
import org.elyonar.fincore.core.orchestration.internal.reconcile.Reconciliation;
import org.elyonar.fincore.core.orchestration.internal.saga.TransferService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Invariant 6, with planted discrepancies — every mismatch class must be flagged, exactly once.
 *
 * <p>The negative assertions carry as much weight as the positive ones: an unreachable ledger
 * must record <em>nothing</em> (a maintenance window is not a discrepancy), and a second pass
 * over an unfixed mismatch must not multiply findings or ops cases.
 */
@SpringBootTest
class ReconciliationTest {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    @Autowired private TenantRegistry tenantRegistry;
    @Autowired private TransferService transfers;
    @Autowired private Reconciliation reconciliation;
    @Autowired @Qualifier("workerJdbcTemplate") private JdbcTemplate workerDb;
    @Autowired @Qualifier("customerJdbcTemplate") private JdbcTemplate customerDb;
    @Autowired @Qualifier("productJdbcTemplate") private JdbcTemplate productDb;
    @Autowired @Qualifier("customerTransactionManager") private PlatformTransactionManager customerTx;
    @Autowired @Qualifier("productTransactionManager") private PlatformTransactionManager productTx;

    private static HttpServer ledger;
    /**
     * POSTs always succeed. GETs answer as configured for the one transaction under test and 503
     * for everything else — the suite shares a database, and sagas completed by other test
     * classes must reconcile as "could not ask" (which records nothing), not as planted
     * discrepancies they are not.
     */
    private static final AtomicInteger readStatus = new AtomicInteger(200);
    private static final AtomicReference<String> readBody = new AtomicReference<>("{}");
    private static final AtomicReference<UUID> readTarget = new AtomicReference<>();
    private static final AtomicReference<UUID> postedTransaction = new AtomicReference<>();

    private UUID tenantId;
    private UUID customerId;
    private UUID fromAccount;

    @BeforeAll
    static void startLedger() throws IOException {
        ledger = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ledger.createContext(
                "/",
                exchange -> {
                    byte[] bytes;
                    int status;
                    if ("GET".equals(exchange.getRequestMethod())) {
                        UUID target = readTarget.get();
                        boolean underTest =
                                target != null && exchange.getRequestURI().getPath().endsWith(target.toString());
                        status = underTest ? readStatus.get() : 503;
                        bytes = (underTest ? readBody.get() : "{}").getBytes(StandardCharsets.UTF_8);
                    } else {
                        UUID id = UUID.randomUUID();
                        postedTransaction.set(id);
                        status = 201;
                        bytes = ("{\"transactionId\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8);
                    }
                    exchange.getRequestBody().readAllBytes();
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
        registry.add("fincore.core.reconciliation.interval-ms", () -> "3600000");
        // Anchors this class to its own application context (and therefore its own stub ledger):
        // several classes register the same dynamic property names, and a shared context would
        // point this suite at another class's stub.
        registry.add("fincore.test.context", () -> "reconciliation");
    }

    @BeforeEach
    void seed() {
        tenantId = UUID.randomUUID();
        tenantRegistry.register(tenantId, "test tenant", "test");
        customerId = UUID.randomUUID();
        fromAccount = UUID.randomUUID();

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
                                                    + " status, created_by, published_by)"
                                                    + " VALUES (?,?,1,'PUBLISHED','user:author','user:publisher') RETURNING id",
                                            UUID.class, tenantId, productId);
                            productDb.update(
                                    "INSERT INTO product.limit_rules (tenant_id, product_version_id, kyc_tier,"
                                            + " channel, limit_type, max_amount_minor, currency)"
                                            + " VALUES (?,?, 'TIER_2', 'API', 'PER_TXN', 5000000, 'NGN')",
                                    tenantId, versionId);
                        });
    }

    private UUID completeTransfer(long amountMinor) {
        String key = "rec-" + UUID.randomUUID();
        transfers.transfer(
                new TransferCommand(
                        tenantId, key, "fp-" + key, customerId, fromAccount, UUID.randomUUID(), null,
                        amountMinor, "NGN", "P", "API", "test", "user:ada", "core", LAGOS));
        UUID posted = postedTransaction.get();
        readTarget.set(posted);
        return posted;
    }

    /** Answers reads with a well-formed transaction whose debits total the given amount. */
    private static void ledgerAnswers(UUID transactionId, long debitMinor) {
        readStatus.set(200);
        readBody.set(
                """
                {"transactionId":"%s","status":"POSTED","entries":[
                  {"accountId":"%s","direction":"DEBIT","amountMinor":"%d","currency":"NGN"},
                  {"accountId":"%s","direction":"CREDIT","amountMinor":"%d","currency":"NGN"}]}
                """
                        .formatted(transactionId, UUID.randomUUID(), debitMinor, UUID.randomUUID(), debitMinor));
    }

    private long count(String table, String kind) {
        return workerJdbcCount(
                "SELECT count(*) FROM orchestration." + table + " WHERE tenant_id = ? AND kind = ?", kind);
    }

    private long workerJdbcCount(String sql, String kind) {
        Long n = workerDb.queryForObject(sql, Long.class, tenantId, kind);
        return n == null ? 0 : n;
    }

    @Test
    void an_agreeing_ledger_yields_no_findings() {
        UUID posted = completeTransfer(50_000);
        ledgerAnswers(posted, 50_000);

        assertThat(reconciliation.run()).isZero();
        assertThat(count("reconciliation_findings", "LEDGER_MISSING")).isZero();
        assertThat(count("reconciliation_findings", "AMOUNT_MISMATCH")).isZero();
    }

    @Test
    void a_completed_saga_the_ledger_never_saw_is_flagged_once() {
        completeTransfer(50_000);
        readStatus.set(404);
        readBody.set("{\"code\":\"TRANSACTION_NOT_FOUND\"}");

        assertThat(reconciliation.run()).isEqualTo(1);
        assertThat(count("reconciliation_findings", "LEDGER_MISSING")).isEqualTo(1);
        assertThat(count("ops_cases", "RECONCILIATION_MISMATCH")).isEqualTo(1);

        // The mismatch is still there next run; the record of it must not multiply.
        assertThat(reconciliation.run()).isZero();
        assertThat(count("reconciliation_findings", "LEDGER_MISSING")).isEqualTo(1);
        assertThat(count("ops_cases", "RECONCILIATION_MISMATCH")).isEqualTo(1);
    }

    @Test
    void debits_that_disagree_with_the_decided_amount_are_flagged() {
        UUID posted = completeTransfer(50_000);
        ledgerAnswers(posted, 49_000);

        assertThat(reconciliation.run()).isEqualTo(1);
        assertThat(count("reconciliation_findings", "AMOUNT_MISMATCH")).isEqualTo(1);
        assertThat(count("ops_cases", "RECONCILIATION_MISMATCH")).isEqualTo(1);
    }

    @Test
    void an_unreachable_ledger_records_nothing() {
        completeTransfer(50_000);
        readStatus.set(503);
        readBody.set("{}");

        assertThat(reconciliation.run()).isZero();
        assertThat(count("reconciliation_findings", "LEDGER_MISSING")).isZero();
        assertThat(count("ops_cases", "RECONCILIATION_MISMATCH")).isZero();
    }

    @Test
    void findings_are_evidence_and_cannot_be_edited() {
        completeTransfer(50_000);
        readStatus.set(404);
        readBody.set("{}");
        reconciliation.run();

        // Two independent defences refuse the edit: the worker role holds no UPDATE grant, and
        // the append-only trigger stops even the owner. Whichever answers first, the row stays.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                workerDb.update(
                                        "UPDATE orchestration.reconciliation_findings SET detail = 'tidied'"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
