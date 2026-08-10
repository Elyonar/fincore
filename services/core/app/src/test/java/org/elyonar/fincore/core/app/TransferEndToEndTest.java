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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A transfer, end to end, through all three modules.
 *
 * <p>Real PostgreSQL with the real per-module roles, so the schema boundary is exercised rather
 * than assumed. The Ledger is a controllable stub — the point here is Core's own three phases and
 * what each outcome does to the saga and its reservation; the suite that runs against a real Ledger
 * is the contract suite, and it is a different thing.
 */
@SpringBootTest
class TransferEndToEndTest {

    // Every tenant a test uses must be registered, because Core now refuses one it has
    // never heard of. Registering here rather than weakening the gate for tests: a guard
    // switched off under test is a guard nobody has tested.
    @Autowired private TenantRegistry tenantRegistry;

    private static HttpServer ledger;
    private static final AtomicInteger status = new AtomicInteger(201);
    private static final AtomicReference<String> body = new AtomicReference<>();
    private static final AtomicInteger callsReceived = new AtomicInteger();
    private static final AtomicReference<String> lastIdempotencyKey = new AtomicReference<>();

    @Autowired private TransferService transfers;
    @Autowired @Qualifier("customerJdbcTemplate") private JdbcTemplate customerDb;
    @Autowired @Qualifier("productJdbcTemplate") private JdbcTemplate productDb;
    @Autowired @Qualifier("orchestrationJdbcTemplate") private JdbcTemplate orchestrationDb;

    @Autowired @Qualifier("customerTransactionManager") private PlatformTransactionManager customerTx;
    @Autowired @Qualifier("productTransactionManager") private PlatformTransactionManager productTx;
    @Autowired @Qualifier("orchestrationTransactionManager") private PlatformTransactionManager orchestrationTx;

    /**
     * Runs work with the tenant context set, inside a transaction.
     *
     * <p>Necessary rather than convenient: the context is {@code SET LOCAL}, so it lives for the
     * transaction and no longer. A statement issued outside one sees no tenant, and row-level
     * security correctly refuses it — which is how this test first failed, and is the discipline
     * working rather than an obstacle to route around.
     */
    private void inTenant(PlatformTransactionManager tx, JdbcTemplate db, Runnable work) {
        new TransactionTemplate(tx)
                .executeWithoutResult(
                        s -> {
                            db.queryForObject(
                                    "SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
                            work.run();
                        });
    }

    private UUID tenantId;
    private UUID customerId;
    private UUID fromAccount;
    private UUID toAccount;
    private UUID feeAccount;

    @BeforeAll
    static void startLedgerStub() throws IOException {
        ledger = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ledger.createContext(
                "/",
                exchange -> {
                    callsReceived.incrementAndGet();
                    String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    int keyAt = request.indexOf("\"idempotencyKey\":\"");
                    if (keyAt >= 0) {
                        int from = keyAt + "\"idempotencyKey\":\"".length();
                        lastIdempotencyKey.set(request.substring(from, request.indexOf('"', from)));
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
    static void stopLedgerStub() {
        ledger.stop(0);
    }

    @DynamicPropertySource
    static void pointCoreAtTheStub(DynamicPropertyRegistry registry) {
        registry.add(
                "fincore.core.ledger.base-url",
                () -> "http://127.0.0.1:" + ledger.getAddress().getPort());
    }

    @BeforeEach
    void seedATenant() {
        tenantId = UUID.randomUUID();
        tenantRegistry.register(tenantId, "test tenant", "test");
        customerId = UUID.randomUUID();
        fromAccount = UUID.randomUUID();
        toAccount = UUID.randomUUID();
        feeAccount = UUID.randomUUID();
        status.set(201);
        body.set("{\"transactionId\":\"" + UUID.randomUUID() + "\"}");
        callsReceived.set(0);

        inTenant(
                customerTx,
                customerDb,
                () -> {
                    customerDb.update(
                            "INSERT INTO customer.customers (id, tenant_id, external_ref, full_name, kyc_tier)"
                                    + " VALUES (?,?,?,?, 'TIER_2')",
                            customerId, tenantId, "CUST-" + UUID.randomUUID(), "Ada Okafor");
                    customerDb.update(
                            "INSERT INTO customer.customer_accounts (tenant_id, customer_id, ledger_account_id, currency,"
                                    + " product_code) VALUES (?,?,?, 'NGN', 'AJO_DAILY')",
                            tenantId, customerId, fromAccount);
                });

        inTenant(
                productTx,
                productDb,
                () -> {
                    UUID productId =
                            productDb.queryForObject(
                                    "INSERT INTO product.products (tenant_id, code, name, type)"
                                            + " VALUES (?, 'AJO_DAILY', 'Ajo Daily', 'SAVINGS') RETURNING id",
                                    UUID.class, tenantId);
                    UUID versionId =
                            productDb.queryForObject(
                                    "INSERT INTO product.product_versions (tenant_id, product_id, version, status, created_by, published_by)"
                                            + " VALUES (?,?,1,'DRAFT','user:author',NULL) RETURNING id",
                                    UUID.class, tenantId, productId);
                    // 2.50% capped at ₦500 — integer basis points throughout.
                    productDb.update(
                            "INSERT INTO product.fee_rules (tenant_id, product_version_id, operation, kind, basis_points, cap_minor, currency, fee_account_id)"
                                    + " VALUES (?,?, 'TRANSFER', 'PERCENT', 250, 50000, 'NGN', ?)",
                            tenantId, versionId, feeAccount);
                    productDb.update(
                            "INSERT INTO product.limit_rules (tenant_id, product_version_id, kyc_tier, channel, limit_type, max_amount_minor, currency)"
                                    + " VALUES (?,?, 'TIER_2', 'TELLER', 'PER_TXN', 5000000, 'NGN')",
                            tenantId, versionId);
                    // Published last: a live version's rules are immutable, so seeding the row as
                    // PUBLISHED and then inserting rules is the same write in the wrong order, and
                    // the trigger refuses it.
                    productDb.update(
                            "UPDATE product.product_versions SET status = 'PUBLISHED',"
                                    + " published_by = 'user:admin' WHERE tenant_id = ? AND id = ?",
                            tenantId, versionId);
                });
    }

    private TransferCommand aTransfer(long amountMinor, String key) {
        return new TransferCommand(
                tenantId, key, "fp-" + amountMinor, customerId, fromAccount, toAccount, feeAccount,
                amountMinor, "NGN", "AJO_DAILY", "TELLER", "transfer to Tobi",
                "user:ada.o@branch-01", "core", ZoneId.of("Africa/Lagos"));
    }

    // ------------------------------------------------------------------- happy

    @Test
    void a_transfer_flows_through_all_three_modules_and_posts_once() {
        TransferResult result = transfers.transfer(aTransfer(500_000, "key-happy"));

        assertThat(result.state()).isEqualTo("COMPLETED");
        // 2.50% of ₦5,000.00 = ₦125.00, under the ₦2,000 cap. Integer arithmetic, no rounding drift.
        assertThat(result.feeMinor()).isEqualTo(12_500);
        assertThat(result.productVersion()).isEqualTo(1);
        assertThat(result.ledgerTransactionId()).isNotNull();
        assertThat(callsReceived.get()).isEqualTo(1);

        // The key the Ledger saw is derivable from the saga id — that is what makes a retry safe.
        assertThat(lastIdempotencyKey.get()).isEqualTo("core:" + result.transactionId() + ":post");
    }

    @Test
    void the_reservation_is_consumed_when_the_posting_commits() {
        TransferResult result = transfers.transfer(aTransfer(100_000, "key-consume"));

        assertThat(reservationStatusFor(result.transactionId())).isEqualTo("CONSUMED");
    }

    @Test
    void the_fee_cap_binds() {
        // 2.50% of ₦40,000.00 computes to ₦1,000.00 — above the ₦500 cap, so the cap wins.
        assertThat(transfers.transfer(aTransfer(4_000_000, "key-cap")).feeMinor()).isEqualTo(50_000);
    }

    // ------------------------------------------------------------- definite fail

    @Test
    void a_rejected_posting_fails_the_saga_and_releases_the_reservation() {
        status.set(422);
        body.set("{\"code\":\"INSUFFICIENT_FUNDS\"}");

        var refused =
                catchThrowableOfType(
                        TransferService.TransferRefused.class,
                        () -> transfers.transfer(aTransfer(500_000, "key-refused")));

        assertThat(refused.code()).isEqualTo("INSUFFICIENT_FUNDS");
        UUID sagaId = sagaIdFor("key-refused");
        assertThat(stateFor(sagaId)).isEqualTo("FAILED");
        // Released only on a definite failure — the money provably did not move.
        assertThat(reservationStatusFor(sagaId)).isEqualTo("RELEASED");
    }

    // ------------------------------------------------------------------ unknown

    @Test
    void an_unknown_outcome_leaves_the_saga_claimable_and_the_reservation_untouched() {
        status.set(500);
        body.set("{\"code\":\"INTERNAL\"}");

        catchThrowableOfType(
                TransferService.OutcomeUnknown.class,
                () -> transfers.transfer(aTransfer(500_000, "key-unknown")));

        UUID sagaId = sagaIdFor("key-unknown");
        assertThat(stateFor(sagaId)).isEqualTo("POSTING");
        // The heart of the protocol: the money may have moved, so nothing is compensated and the
        // headroom stays reserved.
        assertThat(reservationStatusFor(sagaId)).isEqualTo("RESERVED");
        assertThat(attemptOutcomesFor(sagaId)).containsExactly("UNKNOWN");
    }

    // -------------------------------------------------------------- idempotency

    @Test
    void replaying_the_same_key_returns_the_original_result_without_posting_again() {
        TransferResult first = transfers.transfer(aTransfer(500_000, "key-replay"));
        TransferResult second = transfers.transfer(aTransfer(500_000, "key-replay"));

        assertThat(second.transactionId()).isEqualTo(first.transactionId());
        assertThat(second.ledgerTransactionId()).isEqualTo(first.ledgerTransactionId());
        // One posting, not two. The replay never reached the Ledger at all.
        assertThat(callsReceived.get()).isEqualTo(1);
    }

    @Test
    void the_same_key_with_different_economics_is_loud() {
        transfers.transfer(aTransfer(500_000, "key-reuse"));

        assertThat(
                        catchThrowableOfType(
                                TransferService.IdempotencyKeyReused.class,
                                () -> transfers.transfer(aTransfer(900_000, "key-reuse"))))
                .isNotNull();
    }

    // ---------------------------------------------------------------- refusals

    @Test
    void a_transfer_from_an_account_the_customer_does_not_hold_is_refused() {
        TransferCommand strayAccount =
                new TransferCommand(
                        tenantId, "key-stray", "fp", customerId, UUID.randomUUID(), toAccount, feeAccount,
                        500_000, "NGN", "AJO_DAILY", "TELLER", "d", "user:ada", "core",
                        ZoneId.of("Africa/Lagos"));

        assertThat(
                        catchThrowableOfType(
                                TransferService.TransferRefused.class,
                                () -> transfers.transfer(strayAccount))
                            .code())
                .isEqualTo("ACCOUNT_NOT_LINKED");
        // Refused before Phase B: nothing reached the Ledger.
        assertThat(callsReceived.get()).isZero();
    }

    @Test
    void an_amount_over_the_per_transaction_limit_is_refused_before_the_ledger_is_called() {
        assertThat(
                        catchThrowableOfType(
                                TransferService.TransferRefused.class,
                                () -> transfers.transfer(aTransfer(9_000_000, "key-over")))
                            .code())
                .isEqualTo("LIMIT_EXCEEDED");
        assertThat(callsReceived.get()).isZero();
    }

    @Test
    void a_dormant_customer_cannot_transact() {
        inTenant(
                customerTx,
                customerDb,
                () -> customerDb.update("UPDATE customer.customers SET status = 'DORMANT' WHERE id = ?", customerId));

        assertThat(
                        catchThrowableOfType(
                                TransferService.TransferRefused.class,
                                () -> transfers.transfer(aTransfer(500_000, "key-dormant")))
                            .code())
                .isEqualTo("CUSTOMER_NOT_ACTIVE");
    }

    // ------------------------------------------------------------------ helpers

    private <T> T readInTenant(java.util.function.Supplier<T> query) {
        var holder = new java.util.concurrent.atomic.AtomicReference<T>();
        inTenant(orchestrationTx, orchestrationDb, () -> holder.set(query.get()));
        return holder.get();
    }

    private UUID sagaIdFor(String key) {
        return readInTenant(
                () ->
                        orchestrationDb.queryForObject(
                                "SELECT id FROM orchestration.sagas WHERE channel_idempotency_key = ?",
                                UUID.class, key));
    }

    private String stateFor(UUID sagaId) {
        return readInTenant(
                () ->
                        orchestrationDb.queryForObject(
                                "SELECT state FROM orchestration.sagas WHERE id = ?", String.class, sagaId));
    }

    private String reservationStatusFor(UUID sagaId) {
        return readInTenant(
                () ->
                        orchestrationDb.queryForObject(
                                "SELECT status FROM orchestration.limit_reservations WHERE saga_id = ?",
                                String.class, sagaId));
    }

    private java.util.List<String> attemptOutcomesFor(UUID sagaId) {
        return readInTenant(
                () ->
                        orchestrationDb.queryForList(
                                "SELECT outcome FROM orchestration.saga_attempts WHERE saga_id = ? ORDER BY attempt_no",
                                String.class, sagaId));
    }
}
