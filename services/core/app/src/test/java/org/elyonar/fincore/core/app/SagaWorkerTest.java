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
import org.elyonar.fincore.core.orchestration.api.TransferResult;
import org.elyonar.fincore.core.orchestration.internal.approval.ApprovalRecords;
import org.elyonar.fincore.core.orchestration.internal.saga.ReversalService;
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

    // Every tenant a test uses must be registered, because Core now refuses one it has
    // never heard of. Registering here rather than weakening the gate for tests: a guard
    // switched off under test is a guard nobody has tested.
    @Autowired private TenantRegistry tenantRegistry;

    private static HttpServer ledger;
    private static final AtomicInteger status = new AtomicInteger(500);
    private static final AtomicReference<String> body = new AtomicReference<>("{}");
    private static final AtomicReference<String> lastKey = new AtomicReference<>();
    private static final AtomicReference<String> lastPath = new AtomicReference<>();

    @Autowired private TransferService transfers;
    @Autowired private ReversalService reversals;
    @Autowired private ApprovalRecords approvals;
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
                    lastPath.set(exchange.getRequestURI().getPath());
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
                                    "INSERT INTO customer.customer_accounts (tenant_id, customer_id, ledger_account_id, currency,"
                                            + " product_code) VALUES (?,?,?, 'NGN', 'P')",
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
                                            "INSERT INTO product.product_versions (tenant_id, product_id, version, status,"
                                                    + " created_by, published_by)"
                                                    + " VALUES (?,?,1,'DRAFT','user:author',NULL) RETURNING id",
                                            UUID.class, tenantId, productId);
                            productDb.update(
                                    "INSERT INTO product.limit_rules (tenant_id, product_version_id, kyc_tier, channel,"
                                            + " limit_type, max_amount_minor, currency)"
                                            + " VALUES (?,?, 'TIER_2', 'TELLER', 'PER_TXN', 5000000, 'NGN')",
                                    tenantId, versionId);
                            // Published last: a live version's rules are immutable, so seeding the row
                            // as PUBLISHED and then inserting rules is the same write in the wrong
                            // order and the trigger refuses it.
                            productDb.update(
                                    "UPDATE product.product_versions SET status = 'PUBLISHED',"
                                            + " published_by = 'admin' WHERE tenant_id = ? AND id = ?",
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

    private int attemptsOf(UUID sagaId) {
        Integer attempts = workerDb.queryForObject(
                "SELECT attempts FROM orchestration.sagas WHERE id = ?", Integer.class, sagaId);
        return attempts == null ? 0 : attempts;
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

    // ------------------------------------------------------------- reversal sagas

    /**
     * A reversal saga stuck in POSTING: the synchronous {@code ledger.reverse} came back unknown.
     *
     * <p>Zero-fee deliberately (this class seeds no fee rule), because zero-fee was the exact trap:
     * with a fee the old rebuild-as-posting path at least tripped the fee-account guard and
     * escalated (with the wrong diagnosis); without one it NPE'd inside the client on the reversal's
     * deliberately-null accounts, outside every catch that could schedule a retry, and looped on
     * lease expiry forever.
     */
    private UUID aStuckReversal(String key) {
        status.set(201);
        body.set("{\"transactionId\":\"" + UUID.randomUUID() + "\"}");
        TransferResult original =
                transfers.transfer(
                        new TransferCommand(
                                tenantId, key + "-original", "fp", customerId, fromAccount, UUID.randomUUID(),
                                UUID.randomUUID(), 100_000, "NGN", "P", "TELLER", "d",
                                "user:ada", "core", ZoneId.of("Africa/Lagos")));
        UUID approvalId =
                approvals.raise(tenantId, original.transactionId(), original.amountMinor(), "user:ada", null);
        approvals.check(tenantId, approvalId, true, "user:tobi", null);

        status.set(500);
        body.set("{\"code\":\"INTERNAL\"}");
        var stuck =
                catchThrowableOfType(
                        TransferService.OutcomeUnknown.class,
                        () -> reversals.reverse(tenantId, original.transactionId(), approvalId, key, "user:tobi"));
        return stuck.transactionId();
    }

    @Test
    void a_stuck_reversal_is_re_driven_through_ledger_reverse_under_its_original_key() {
        UUID reversalId = aStuckReversal("w-rev-redrive");
        assertThat(stateOf(reversalId)).isEqualTo("POSTING");
        UUID originalLedgerTransaction =
                workerDb.queryForObject(
                        "SELECT original.ledger_transaction_id FROM orchestration.sagas r"
                                + " JOIN orchestration.sagas original ON original.id = r.reverses_saga_id"
                                + " WHERE r.id = ?",
                        UUID.class, reversalId);

        // The Ledger recovers and answers the replayed reversal definitively.
        status.set(201);
        body.set("{\"transactionId\":\"" + UUID.randomUUID() + "\"}");
        worker.resolve(reversalId);

        assertThat(stateOf(reversalId)).isEqualTo("COMPLETED");
        // The recovery is the same call the synchronous path made: ledger.reverse against the
        // original's ledger transaction, under the same :reverse key — never a rebuilt posting
        // under :post, which the Ledger's registry would not recognise as a replay.
        assertThat(lastKey.get()).isEqualTo("core:" + reversalId + ":reverse");
        assertThat(lastPath.get())
                .isEqualTo("/v1/transactions/" + originalLedgerTransaction + "/reverse");
    }

    @Test
    void a_stuck_reversal_terminates_at_the_bound_with_an_ops_case_rather_than_looping() {
        // The register's regression: a reversal saga left in POSTING and driven through the worker
        // must terminate — COMPLETED via ledger.reverse or escalated — never loop.
        UUID reversalId = aStuckReversal("w-rev-escalate");
        workerDb.update("UPDATE orchestration.sagas SET attempts = 20 WHERE id = ?", reversalId);

        // Ledger still unhealthy: the outcome stays unknown, so past the bound a human owns it.
        worker.resolve(reversalId);

        assertThat(stateOf(reversalId)).isEqualTo("PENDING_RESOLUTION");
        assertThat(
                        workerDb.queryForObject(
                                "SELECT count(*) FROM orchestration.ops_cases"
                                        + " WHERE saga_id = ? AND status = 'OPEN' AND kind = 'UNRESOLVED_OUTCOME'",
                                Integer.class, reversalId))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------- worker-side failures

    @Test
    void a_worker_side_failure_backs_off_and_escalates_rather_than_looping_silently() {
        // A saga the worker itself cannot process — broken by construction so the failure happens
        // inside Core, not at the Ledger. The old per-saga catch only logged, so nothing advanced
        // next_attempt_at and lease expiry re-offered the saga immediately, forever.
        UUID sagaId = aStuckSaga("w-worker-failure");
        workerDb.update(
                "UPDATE orchestration.sagas SET from_account_id = NULL, next_attempt_at = now()"
                        + " WHERE id = ?",
                sagaId);

        // Driven through the scheduled pass rather than resolve() directly, because the property
        // under test is the pass's own catch. Bounded passes rather than one: the suite shares a
        // database, each pass claims a batch of 25 oldest-first, and every pass pushes what it
        // claimed into backoff — so the target saga is reached within a few passes regardless of
        // what other suites left behind.
        for (int pass = 0; pass < 20 && attemptsOf(sagaId) < 2; pass++) {
            worker.resolveOutstanding();
        }

        // Still undetermined — but the attempt was counted and the retry backed off.
        assertThat(stateOf(sagaId)).isEqualTo("POSTING");
        assertThat(attemptsOf(sagaId)).isEqualTo(2);
        assertThat(
                        workerDb.queryForObject(
                                "SELECT next_attempt_at > now() FROM orchestration.sagas WHERE id = ?",
                                Boolean.class, sagaId))
                .isTrue();

        // Past the bound the same failure escalates instead of retrying.
        workerDb.update(
                "UPDATE orchestration.sagas SET attempts = 20, next_attempt_at = now() WHERE id = ?", sagaId);
        for (int pass = 0; pass < 20 && "POSTING".equals(stateOf(sagaId)); pass++) {
            worker.resolveOutstanding();
        }

        assertThat(stateOf(sagaId)).isEqualTo("PENDING_RESOLUTION");
        assertThat(
                        workerDb.queryForObject(
                                "SELECT count(*) FROM orchestration.ops_cases WHERE saga_id = ? AND status = 'OPEN'",
                                Integer.class, sagaId))
                .isEqualTo(1);
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
