package org.elyonar.fincore.core.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.elyonar.fincore.core.orchestration.api.TransferCommand;
import org.elyonar.fincore.core.orchestration.internal.saga.SagaWorker;
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
 * The worker turning an unknown outcome into a determined one.
 *
 * <p>This is what makes {@code UNKNOWN} temporary. The worker never decides what happened — it
 * re-sends the same derived key and lets the Ledger answer, which is why both branches converge on
 * the truth rather than on a guess.
 */
@SpringBootTest
class SagaWorkerTest {

    private static HttpServer ledger;
    private static final AtomicInteger status = new AtomicInteger(500);
    private static final AtomicReference<String> body = new AtomicReference<>("{}");
    private static final AtomicReference<String> lastKey = new AtomicReference<>();

    @Autowired private TransferService transfers;
    @Autowired private SagaWorker worker;
    @Autowired @Qualifier("customerJdbcTemplate") private JdbcTemplate customerDb;
    @Autowired @Qualifier("productJdbcTemplate") private JdbcTemplate productDb;
    @Autowired @Qualifier("workerJdbcTemplate") private JdbcTemplate workerDb;
    @Autowired @Qualifier("customerTransactionManager") private PlatformTransactionManager customerTx;
    @Autowired @Qualifier("productTransactionManager") private PlatformTransactionManager productTx;

    private UUID tenantId;
    private UUID customerId;
    private UUID fromAccount;

    @BeforeAll
    static void startLedger() throws IOException {
        ledger = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ledger.createContext(
                "/",
                exchange -> {
                    String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    int at = request.indexOf("\"idempotencyKey\":\"");
                    if (at >= 0) {
                        int from = at + "\"idempotencyKey\":\"".length();
                        lastKey.set(request.substring(from, request.indexOf('"', from)));
                    }
                    byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status.get(), bytes.length);
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
        // The scheduled pass is disabled: these drive the worker directly so the assertions are
        // about what it does, not about when a timer happened to fire.
        registry.add("fincore.core.worker.interval-ms", () -> "3600000");
    }

    @BeforeEach
    void seed() {
        tenantId = UUID.randomUUID();
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
                                    "INSERT INTO customer.customer_accounts (tenant_id, customer_id, ledger_account_id, currency)"
                                            + " VALUES (?,?,?, 'NGN')",
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
                                            "INSERT INTO product.product_versions (tenant_id, product_id, version, status, published_by)"
                                                    + " VALUES (?,?,1,'PUBLISHED','admin') RETURNING id",
                                            UUID.class, tenantId, productId);
                            productDb.update(
                                    "INSERT INTO product.limit_rules (tenant_id, product_version_id, kyc_tier, channel,"
                                            + " limit_type, max_amount_minor, currency)"
                                            + " VALUES (?,?, 'TIER_2', 'TELLER', 'PER_TXN', 5000000, 'NGN')",
                                    tenantId, versionId);
                        });
    }

    /** Leaves a saga stuck in POSTING by making the first call time out. */
    private UUID aStuckSaga(String key) {
        status.set(500);
        body.set("{\"code\":\"INTERNAL\"}");
        var stuck =
                catchThrowableOfType(
                        TransferService.OutcomeUnknown.class,
                        () ->
                                transfers.transfer(
                                        new TransferCommand(
                                                tenantId, key, "fp", customerId, fromAccount, UUID.randomUUID(),
                                                UUID.randomUUID(), 100_000, "NGN", "P", "TELLER", "d",
                                                "user:ada", "core", ZoneId.of("Africa/Lagos"))));
        return stuck.transactionId();
    }

    private String stateOf(UUID sagaId) {
        return workerDb.queryForObject(
                "SELECT state FROM orchestration.sagas WHERE id = ?", String.class, sagaId);
    }

    @Test
    void the_worker_resolves_an_unknown_as_posted_when_the_ledger_says_so() {
        UUID sagaId = aStuckSaga("w-posted");
        assertThat(stateOf(sagaId)).isEqualTo("POSTING");

        // The Ledger recovers and answers definitively.
        status.set(201);
        body.set("{\"transactionId\":\"" + UUID.randomUUID() + "\"}");
        worker.resolve(sagaId);

        assertThat(stateOf(sagaId)).isEqualTo("COMPLETED");
        assertThat(
                        workerDb.queryForObject(
                                "SELECT status FROM orchestration.limit_reservations WHERE saga_id = ?",
                                String.class, sagaId))
                .isEqualTo("CONSUMED");
    }

    @Test
    void the_worker_retries_the_same_derived_key() {
        // The whole reason retrying is safe: the Ledger's registry recognises the replay. A fresh
        // key would post a second transaction instead.
        UUID sagaId = aStuckSaga("w-samekey");

        status.set(201);
        body.set("{\"transactionId\":\"" + UUID.randomUUID() + "\"}");
        worker.resolve(sagaId);

        assertThat(lastKey.get()).isEqualTo("core:" + sagaId + ":post");
    }

    @Test
    void the_worker_resolves_an_unknown_as_not_posted_and_releases_the_reservation() {
        UUID sagaId = aStuckSaga("w-notposted");

        status.set(422);
        body.set("{\"code\":\"INSUFFICIENT_FUNDS\"}");
        worker.resolve(sagaId);

        assertThat(stateOf(sagaId)).isEqualTo("FAILED");
        assertThat(
                        workerDb.queryForObject(
                                "SELECT status FROM orchestration.limit_reservations WHERE saga_id = ?",
                                String.class, sagaId))
                .isEqualTo("RELEASED");
    }

    @Test
    void a_still_unknown_saga_stays_claimable_and_keeps_its_reservation() {
        UUID sagaId = aStuckSaga("w-stillunknown");

        // Ledger still unhealthy.
        worker.resolve(sagaId);

        assertThat(stateOf(sagaId)).isEqualTo("POSTING");
        assertThat(
                        workerDb.queryForObject(
                                "SELECT status FROM orchestration.limit_reservations WHERE saga_id = ?",
                                String.class, sagaId))
                .isEqualTo("RESERVED");
    }

    @Test
    void past_the_bound_it_escalates_to_an_ops_case_without_compensating() {
        UUID sagaId = aStuckSaga("w-escalate");
        // Simulate having exhausted the attempt budget.
        workerDb.update("UPDATE orchestration.sagas SET attempts = 20 WHERE id = ?", sagaId);

        worker.resolve(sagaId);

        assertThat(stateOf(sagaId)).isEqualTo("PENDING_RESOLUTION");
        assertThat(
                        workerDb.queryForObject(
                                "SELECT count(*) FROM orchestration.ops_cases WHERE saga_id = ? AND status = 'OPEN'",
                                Integer.class, sagaId))
                .isEqualTo(1);
        // The money may have moved, so nothing is released. Holding the headroom is the point:
        // freeing it would let a second transfer breach the limit if the first did commit.
        assertThat(
                        workerDb.queryForObject(
                                "SELECT status FROM orchestration.limit_reservations WHERE saga_id = ?",
                                String.class, sagaId))
                .isEqualTo("RESERVED");
    }
}
