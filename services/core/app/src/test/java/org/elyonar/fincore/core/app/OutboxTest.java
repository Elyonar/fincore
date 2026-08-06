package org.elyonar.fincore.core.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.elyonar.fincore.core.orchestration.api.TransferCommand;
import org.elyonar.fincore.core.orchestration.internal.outbox.OutboxRelay;
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
 * The outbox: an event exists if and only if the change committed, and the relay never skips one.
 *
 * <p>The second property is the subtle one. Sequence values are assigned at insert, not at commit,
 * so a slow transaction can commit a low id after a higher one was already relayed. A watermark
 * poll — "everything since the last id I saw" — loses that row permanently and silently. The test
 * here reproduces exactly that interleaving.
 */
@SpringBootTest
class OutboxTest {

    private static HttpServer ledger;
    private static final AtomicInteger ledgerStatus = new AtomicInteger(201);
    private static final AtomicReference<String> ledgerBody = new AtomicReference<>("{}");

    @Autowired private TransferService transfers;
    @Autowired private OutboxRelay relay;
    @Autowired @Qualifier("customerJdbcTemplate") private JdbcTemplate customerDb;
    @Autowired @Qualifier("productJdbcTemplate") private JdbcTemplate productDb;
    @Autowired @Qualifier("relayJdbcTemplate") private JdbcTemplate relayDb;
    @Autowired @Qualifier("orchestrationJdbcTemplate") private JdbcTemplate orchestrationDb;
    @Autowired @Qualifier("customerTransactionManager") private PlatformTransactionManager customerTx;
    @Autowired @Qualifier("productTransactionManager") private PlatformTransactionManager productTx;
    @Autowired @Qualifier("orchestrationTransactionManager") private PlatformTransactionManager orchestrationTx;
    // A connection opened outside the pool. The slow writer holds one open across a transfer that
    // needs its own, so borrowing both from the same pool would starve it rather than test
    // anything — and the interleaving under test is precisely two independent connections.
    @org.springframework.beans.factory.annotation.Value("${fincore.core.datasource.orchestration.jdbc-url}")
    private String orchestrationUrl;

    private UUID tenantId;
    private UUID customerId;
    private UUID fromAccount;

    @BeforeAll
    static void startLedger() throws IOException {
        ledger = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ledger.createContext(
                "/",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
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
        fromAccount = UUID.randomUUID();
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
                                                    + " status, created_by, published_by) VALUES (?,?,1,'PUBLISHED','user:author','user:publisher') RETURNING id",
                                            UUID.class, tenantId, productId);
                            productDb.update(
                                    "INSERT INTO product.limit_rules (tenant_id, product_version_id, kyc_tier,"
                                            + " channel, limit_type, max_amount_minor, currency)"
                                            + " VALUES (?,?, 'TIER_2', 'TELLER', 'PER_TXN', 5000000, 'NGN')",
                                    tenantId, versionId);
                        });
    }

    private UUID transfer(String key) {
        return transfers
                .transfer(
                        new TransferCommand(
                                tenantId, key, "fp", customerId, fromAccount, UUID.randomUUID(),
                                UUID.randomUUID(), 100_000, "NGN", "P", "TELLER", "d",
                                "user:ada", "core", ZoneId.of("Africa/Lagos")))
                .transactionId();
    }

    private UUID sagaIdFor(String key) {
        var holder = new java.util.concurrent.atomic.AtomicReference<UUID>();
        new TransactionTemplate(orchestrationTx)
                .executeWithoutResult(
                        s -> {
                            orchestrationDb.queryForObject(
                                    "SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
                            holder.set(
                                    orchestrationDb.queryForObject(
                                            "SELECT id FROM orchestration.sagas WHERE channel_idempotency_key = ?",
                                            UUID.class, key));
                        });
        return holder.get();
    }

    private List<String> eventsFor(UUID sagaId) {
        return relayDb.queryForList(
                "SELECT event_type FROM orchestration.outbox_events WHERE aggregate_id = ? ORDER BY id",
                String.class, sagaId.toString());
    }

    // ------------------------------------------------------- exists iff committed

    @Test
    void a_completed_transfer_leaves_initiated_and_completed() {
        UUID sagaId = transfer("ob-ok");

        assertThat(eventsFor(sagaId)).containsExactly("transfer.initiated", "transfer.completed");
    }

    @Test
    void a_refused_transfer_leaves_initiated_and_failed() {
        ledgerStatus.set(422);
        ledgerBody.set("{\"code\":\"INSUFFICIENT_FUNDS\"}");

        catchThrowableOfType(TransferService.TransferRefused.class, () -> transfer("ob-failed"));

        UUID sagaId = sagaIdFor("ob-failed");
        assertThat(eventsFor(sagaId)).containsExactly("transfer.initiated", "transfer.failed");
    }

    @Test
    void an_unknown_outcome_emits_nothing_beyond_initiated() {
        // There is nothing yet to say happened. Emitting a completion or a failure here would tell
        // consumers something the platform does not know.
        ledgerStatus.set(500);
        ledgerBody.set("{}");

        var unknown =
                catchThrowableOfType(TransferService.OutcomeUnknown.class, () -> transfer("ob-unknown"));

        assertThat(eventsFor(unknown.transactionId())).containsExactly("transfer.initiated");
    }

    @Test
    void a_rejected_request_leaves_no_event_at_all() {
        // Rejections are total: no saga, no reservation, no event. The rollback takes the event
        // with it, which is the property an outbox exists to provide.
        catchThrowableOfType(
                TransferService.TransferRefused.class,
                () ->
                        transfers.transfer(
                                new TransferCommand(
                                        tenantId, "ob-rejected", "fp", customerId,
                                        UUID.randomUUID(), // an account this customer does not hold
                                        UUID.randomUUID(), UUID.randomUUID(), 100_000, "NGN", "P",
                                        "TELLER", "d", "user:ada", "core", ZoneId.of("Africa/Lagos"))));

        // Asserted without joining sagas on purpose: core_relay has no grant there, because a
        // delivery component has no business reading a saga. The boundary refuses the join.
        assertThat(
                        relayDb.queryForObject(
                                "SELECT count(*) FROM orchestration.outbox_events"
                                        + " WHERE created_at > now() - INTERVAL '1 minute'"
                                        + "   AND tenant_id = ?",
                                Integer.class, tenantId))
                .isZero();
    }

    // ----------------------------------------------------------------- the relay

    @Test
    void the_relay_publishes_pending_events_and_marks_them() {
        UUID sagaId = transfer("ob-relay");

        assertThat(relay.publishBatch(100)).isPositive();

        assertThat(
                        relayDb.queryForObject(
                                "SELECT count(*) FROM orchestration.outbox_events"
                                        + " WHERE aggregate_id = ? AND published_at IS NULL",
                                Integer.class, sagaId.toString()))
                .isZero();
    }

    @Test
    void a_published_event_is_not_published_twice() {
        transfer("ob-once");
        relay.publishBatch(100);

        assertThat(relay.publishBatch(100)).isZero();
    }

    @Test
    void an_event_committed_after_a_later_one_was_relayed_is_still_published() {
        // The watermark trap, reproduced. Ids are assigned at insert, so this row takes a low id
        // and commits late — after a higher id has already been relayed. A poll of "everything
        // since the last id I saw" would skip it forever, silently.
        UUID slowSaga = UUID.randomUUID();
        long lowId;

        // A genuinely separate connection with manual commit. Using the shared transaction manager
        // would enlist this in the surrounding transaction, and nothing would be concurrent.
        try (var connection =
                java.sql.DriverManager.getConnection(
                        orchestrationUrl, "core_orchestration", "core_orchestration")) {
            connection.setAutoCommit(false);
            try (var scope = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                scope.setString(1, tenantId.toString());
                scope.execute();
            }
            try (var insert =
                    connection.prepareStatement(
                            "INSERT INTO orchestration.outbox_events (tenant_id, event_type, aggregate_id, payload)"
                                    + " VALUES (?, 'transfer.initiated', ?, '{}'::jsonb) RETURNING id")) {
                insert.setObject(1, tenantId);
                insert.setString(2, slowSaga.toString());
                try (var rs = insert.executeQuery()) {
                    rs.next();
                    lowId = rs.getLong(1);
                }
            }

            // Still uncommitted. Meanwhile a later event is written and relayed.
            UUID fastSaga = transfer("ob-latecommit");
            assertThat(relay.publishBatch(100)).isPositive();
            assertThat(
                            relayDb.queryForObject(
                                    "SELECT count(*) FROM orchestration.outbox_events"
                                            + " WHERE aggregate_id = ? AND published_at IS NOT NULL",
                                    Integer.class, fastSaga.toString()))
                    .isPositive();

            // The low id becomes visible only now — after a higher one was already published.
            connection.commit();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }

        relay.publishBatch(100);

        assertThat(
                        relayDb.queryForObject(
                                "SELECT published_at IS NOT NULL FROM orchestration.outbox_events WHERE id = ?",
                                Boolean.class, lowId))
                .isTrue();
    }

    @Test
    void staleness_is_measurable_so_a_dead_relay_is_visible() {
        transfer("ob-stale");

        // The signal a monitor alerts on. Without it a stopped relay is invisible until a consumer
        // is noticed to have gone quiet — typically at month-end.
        assertThat(relay.oldestPendingAgeSeconds()).isPresent();
    }
}
