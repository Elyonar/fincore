package org.elyonar.fincore.core.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.elyonar.fincore.core.orchestration.api.TransferCommand;
import org.elyonar.fincore.core.orchestration.internal.saga.SagaClaims;
import org.elyonar.fincore.core.orchestration.internal.saga.SagaRecords;
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
 * The failure-injection suite — the service's central claim, tested at its phase boundaries.
 *
 * <p>"Complete or compensated, never partial" is only provable by interrupting the saga at each
 * point a process can die and asserting that recovery converges with what the ledger actually
 * holds. Each scenario here is one crash window: after Phase A commits and before the ledger
 * call; mid-call with the outcome unknown; after the ledger commits but before Phase C records
 * it; and a worker dying mid-lease. The one assertion repeated everywhere is that recovery
 * re-sends the <em>original derived key</em> — convergence by replay, never a second posting.
 *
 * <p>The crash is simulated by construction rather than by killing connections: a saga opened and
 * never driven <em>is</em> the after-Phase-A crash, a stub answering 500 <em>is</em> the lost
 * response. What a real connection kill adds — mid-statement termination inside Phase A itself —
 * is PostgreSQL's transaction atomicity, which the ledger's own suite already leans on.
 */
@SpringBootTest
class FailureInjectionTest {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    @Autowired private TenantRegistry tenantRegistry;
    @Autowired private TransferService transfers;
    @Autowired private SagaRecords sagas;
    @Autowired private SagaWorker worker;
    @Autowired private SagaClaims claims;
    @Autowired private JdbcTemplate orchestrationDb;
    @Autowired @Qualifier("customerJdbcTemplate") private JdbcTemplate customerDb;
    @Autowired @Qualifier("productJdbcTemplate") private JdbcTemplate productDb;
    @Autowired @Qualifier("customerTransactionManager") private PlatformTransactionManager customerTx;
    @Autowired @Qualifier("productTransactionManager") private PlatformTransactionManager productTx;
    @Autowired @Qualifier("orchestrationTransactionManager") private PlatformTransactionManager orchestrationTx;

    private static HttpServer ledger;
    private static final AtomicInteger postStatus = new AtomicInteger(201);
    private static final AtomicReference<UUID> fixedTransactionId = new AtomicReference<>();
    private static final List<String> postedKeys = new CopyOnWriteArrayList<>();
    private static final Pattern KEY = Pattern.compile("\"idempotencyKey\"\\s*:\\s*\"([^\"]+)\"");

    private UUID tenantId;
    private UUID customerId;
    private UUID fromAccount;

    @BeforeAll
    static void startLedger() throws IOException {
        ledger = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ledger.createContext(
                "/",
                exchange -> {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    if ("POST".equals(exchange.getRequestMethod())) {
                        Matcher key = KEY.matcher(body);
                        if (key.find()) {
                            postedKeys.add(key.group(1));
                        }
                    }
                    int status = postStatus.get();
                    UUID id = fixedTransactionId.get() == null ? UUID.randomUUID() : fixedTransactionId.get();
                    byte[] bytes =
                            (status >= 200 && status < 300
                                            ? "{\"transactionId\":\"" + id + "\"}"
                                            : "{\"code\":\"INTERNAL\"}")
                                    .getBytes(StandardCharsets.UTF_8);
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
        registry.add("fincore.test.context", () -> "failure-injection");
    }

    @BeforeEach
    void seed() {
        tenantId = UUID.randomUUID();
        tenantRegistry.register(tenantId, "test tenant", "test");
        customerId = UUID.randomUUID();
        fromAccount = UUID.randomUUID();
        postStatus.set(201);
        fixedTransactionId.set(null);
        postedKeys.clear();

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
                                                    + " VALUES (?,?,1,'DRAFT','user:author',NULL) RETURNING id",
                                            UUID.class, tenantId, productId);
                            productDb.update(
                                    "INSERT INTO product.limit_rules (tenant_id, product_version_id, kyc_tier,"
                                            + " channel, limit_type, max_amount_minor, currency)"
                                            + " VALUES (?,?, 'TIER_2', 'API', 'PER_TXN', 5000000, 'NGN')",
                                    tenantId, versionId);
                            // Published last, because pricing for a live version is immutable (V7):
                            // a rule added after publish would change what an already-decided transaction
                            // was priced under, and the database refuses it.
                            productDb.update(
                                    "UPDATE product.product_versions SET status = 'PUBLISHED',"
                                            + " published_by = 'user:publisher' WHERE tenant_id = ? AND id = ?",
                                    tenantId, versionId);
                        });
    }

    private TransferCommand command(String key, long amountMinor) {
        return new TransferCommand(
                tenantId, key, "fp-" + key, customerId, fromAccount, UUID.randomUUID(), null,
                amountMinor, "NGN", "P", "API", "test", "user:ada", "core", LAGOS);
    }

    private String sagaState(UUID sagaId) {
        return new TransactionTemplate(orchestrationTx)
                .execute(
                        s -> {
                            orchestrationDb.queryForObject(
                                    "SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
                            return orchestrationDb.queryForObject(
                                    "SELECT state FROM orchestration.sagas WHERE id = ?", String.class, sagaId);
                        });
    }

    @Test
    void a_crash_after_phase_a_and_before_the_ledger_call_converges_on_recovery() {
        // The crash: Phase A committed (saga + reservation + event) and the process died before
        // anything was sent. A saga opened and never driven is exactly that state.
        UUID sagaId = openWithoutDriving("crash-a");
        assertThat(sagaState(sagaId)).isEqualTo("POSTING");
        assertThat(postedKeys).isEmpty();

        worker.resolve(sagaId);

        assertThat(sagaState(sagaId)).isEqualTo("COMPLETED");
        // Recovery sent the original derived key — the posting the crashed request would have sent.
        assertThat(postedKeys).containsExactly("core:" + sagaId + ":post");
    }

    @Test
    void a_lost_response_retries_the_same_key_until_the_answer_is_definitive() {
        // The crash: the call went out and the answer never came back.
        postStatus.set(500);
        var unknown =
                catchThrowableOfType(
                        TransferService.OutcomeUnknown.class,
                        () -> transfers.transfer(command("crash-b", 40_000)));
        assertThat(unknown).isNotNull();
        UUID sagaId = unknown.transactionId();
        assertThat(sagaState(sagaId)).isEqualTo("POSTING");

        // The ledger comes back; the worker re-sends. Same key, both attempts — never a new one.
        postStatus.set(201);
        worker.resolve(sagaId);

        assertThat(sagaState(sagaId)).isEqualTo("COMPLETED");
        assertThat(postedKeys).hasSize(2);
        assertThat(postedKeys.stream().distinct()).containsExactly("core:" + sagaId + ":post");
    }

    @Test
    void a_crash_after_the_ledger_committed_records_the_ledgers_transaction_not_a_new_one() {
        // The crash: the ledger committed, the response (or Phase C) was lost. On re-send the
        // ledger's idempotency replays the original transaction — recovery must record *that* id.
        UUID sagaId = openWithoutDriving("crash-c");
        UUID theLedgersTransaction = UUID.randomUUID();
        fixedTransactionId.set(theLedgersTransaction);

        worker.resolve(sagaId);

        assertThat(sagaState(sagaId)).isEqualTo("COMPLETED");
        UUID recorded =
                new TransactionTemplate(orchestrationTx)
                        .execute(
                                s -> {
                                    orchestrationDb.queryForObject(
                                            "SELECT set_config('app.tenant_id', ?, true)",
                                            String.class,
                                            tenantId.toString());
                                    return orchestrationDb.queryForObject(
                                            "SELECT ledger_transaction_id FROM orchestration.sagas WHERE id = ?",
                                            UUID.class,
                                            sagaId);
                                });
        assertThat(recorded).isEqualTo(theLedgersTransaction);
    }

    @Test
    void a_worker_that_dies_mid_lease_is_reclaimed_and_the_saga_still_converges() {
        UUID sagaId = openWithoutDriving("crash-d");

        // Worker A claims and dies: the lease is taken and never released or heartbeated.
        List<UUID> claimedByA = claims.claim("worker-a", Duration.ofSeconds(30), 25);
        assertThat(claimedByA).contains(sagaId);

        // While the lease lives, nobody else may work it.
        assertThat(claims.claim("worker-b", Duration.ofSeconds(30), 25)).doesNotContain(sagaId);

        // The lease runs out (forced rather than slept: a clock edge, not a race, is the event).
        new TransactionTemplate(orchestrationTx)
                .executeWithoutResult(
                        s2 -> {
                            orchestrationDb.queryForObject(
                                    "SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
                            orchestrationDb.update(
                                    "UPDATE orchestration.sagas SET claim_expires_at = now() - INTERVAL '1 second'"
                                            + " WHERE id = ?",
                                    sagaId);
                        });

        // Worker B reclaims the dead worker's saga and finishes the job — under the original key.
        assertThat(claims.claim("worker-b", Duration.ofSeconds(30), 25)).contains(sagaId);
        worker.resolve(sagaId);

        assertThat(sagaState(sagaId)).isEqualTo("COMPLETED");
        assertThat(postedKeys).containsExactly("core:" + sagaId + ":post");
    }

    /** Phase A alone: the saga exists, reserved and eventful, and nothing has been sent. */
    private UUID openWithoutDriving(String key) {
        var decision =
                org.elyonar.fincore.core.product.api.ProductDecision.permitted(0, null, 5_000_000, null, 1);
        return sagas.open(command(key, 30_000), decision, "TIER_2", "daily:2026-08-08");
    }
}
