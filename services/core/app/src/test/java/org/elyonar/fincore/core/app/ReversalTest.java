package org.elyonar.fincore.core.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
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
 * Business reversal, and the maker-checker control around it.
 *
 * <p>The property under test throughout: an approval authorizes exactly one reversal, of exactly
 * one transaction, for exactly one amount. Anything weaker is not maker-checker — it is a token
 * with two names on it, and every case here is a way that token could otherwise be reused.
 */
@SpringBootTest
class ReversalTest {

    // Every tenant a test uses must be registered, because Core now refuses one it has
    // never heard of. Registering here rather than weakening the gate for tests: a guard
    // switched off under test is a guard nobody has tested.
    @Autowired private TenantRegistry tenantRegistry;

    private static HttpServer ledger;
    private static final AtomicInteger ledgerStatus = new AtomicInteger(201);
    private static final AtomicReference<String> ledgerBody = new AtomicReference<>("{}");

    @Autowired private TransferService transfers;
    @Autowired private ReversalService reversals;
    @Autowired private ApprovalRecords approvals;
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
        tenantRegistry.register(tenantId, "test tenant", "test");
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

    private TransferResult aCompletedTransfer(String key) {
        return transfers.transfer(
                new TransferCommand(
                        tenantId, key, "fp", customerId, fromAccount, UUID.randomUUID(), UUID.randomUUID(),
                        100_000, "NGN", "P", "TELLER", "d", "user:ada", "core", ZoneId.of("Africa/Lagos")));
    }

    /** Raised by one person, checked by another — the whole point of the control. */
    private UUID anApprovalFor(TransferResult transfer) {
        UUID approvalId = approvals.raise(tenantId, transfer.transactionId(), transfer.amountMinor(), "user:ada");
        approvals.check(tenantId, approvalId, true, "user:tobi");
        return approvalId;
    }

    private String stateOf(UUID sagaId) {
        return workerDb.queryForObject(
                "SELECT state FROM orchestration.sagas WHERE id = ?", String.class, sagaId);
    }

    // ------------------------------------------------------------------- happy

    @Test
    void an_approved_reversal_posts_and_leaves_the_original_untouched() {
        TransferResult original = aCompletedTransfer("rev-ok");
        UUID approvalId = anApprovalFor(original);

        ledgerBody.set("{\"transactionId\":\"" + UUID.randomUUID() + "\"}");
        TransferResult reversal =
                reversals.reverse(tenantId, original.transactionId(), approvalId, "rev-key-1", "user:tobi");

        assertThat(reversal.state()).isEqualTo("COMPLETED");
        assertThat(reversal.transactionId()).isNotEqualTo(original.transactionId());
        // A reversal is its own saga. The original stays terminal, and the trail stays additive.
        assertThat(stateOf(original.transactionId())).isEqualTo("COMPLETED");
        assertThat(
                        workerDb.queryForObject(
                                "SELECT reverses_saga_id FROM orchestration.sagas WHERE id = ?",
                                UUID.class, reversal.transactionId()))
                .isEqualTo(original.transactionId());
    }

    @Test
    void already_reversed_converges_on_the_winning_reversal() {
        // Someone else's reversal won. Treating the 409 as a failure would leave this saga
        // retry-looping against an outcome that is already settled.
        TransferResult original = aCompletedTransfer("rev-race");
        UUID approvalId = anApprovalFor(original);

        UUID winner = UUID.randomUUID();
        ledgerStatus.set(409);
        ledgerBody.set("{\"code\":\"ALREADY_REVERSED\",\"reversalTransactionId\":\"" + winner + "\"}");

        TransferResult reversal =
                reversals.reverse(tenantId, original.transactionId(), approvalId, "rev-key-race", "user:tobi");

        assertThat(reversal.state()).isEqualTo("COMPLETED");
        assertThat(reversal.ledgerTransactionId()).isEqualTo(winner);
    }

    // ------------------------------------------------------------- the control

    @Test
    void a_reversal_without_an_approval_is_refused() {
        TransferResult original = aCompletedTransfer("rev-noapproval");

        assertThat(
                        catchThrowable(
                                () ->
                                        reversals.reverse(
                                                tenantId, original.transactionId(), UUID.randomUUID(),
                                                "rev-key-2", "user:tobi")))
                .isInstanceOf(ApprovalRecords.ApprovalRejected.class);
    }

    @Test
    void an_unchecked_approval_does_not_authorize_anything() {
        // Raised but never checked. One signature is not maker-checker.
        TransferResult original = aCompletedTransfer("rev-unchecked");
        UUID pending =
                approvals.raise(tenantId, original.transactionId(), original.amountMinor(), "user:ada");

        assertThat(
                        catchThrowable(
                                () ->
                                        reversals.reverse(
                                                tenantId, original.transactionId(), pending, "rev-key-3", "user:tobi")))
                .isInstanceOf(ApprovalRecords.ApprovalRejected.class);
    }

    @Test
    void the_maker_cannot_be_the_checker() {
        TransferResult original = aCompletedTransfer("rev-selfcheck");
        UUID approvalId =
                approvals.raise(tenantId, original.transactionId(), original.amountMinor(), "user:ada");

        // Refused by a CHECK constraint, not by this method remembering to compare.
        assertThat(catchThrowable(() -> approvals.check(tenantId, approvalId, true, "user:ada")))
                .isNotNull();
    }

    @Test
    void an_approval_is_single_use() {
        // Tested on the approval directly rather than through a second reversal, because the
        // reversal path refuses earlier and for a stronger reason — the transaction is already
        // reversed — and a test that passed on NotReversible would prove nothing about reuse.
        // Without this property, one approval authorizes a second reversal of the same
        // transaction: a double credit with an audit trail that looks impeccable.
        TransferResult original = aCompletedTransfer("rev-reuse");
        UUID approvalId = anApprovalFor(original);

        approvals.consume(tenantId, approvalId, original.transactionId(), original.amountMinor());

        assertThat(
                        catchThrowable(
                                () ->
                                        approvals.consume(
                                                tenantId, approvalId, original.transactionId(),
                                                original.amountMinor())))
                .isInstanceOf(ApprovalRecords.ApprovalRejected.class);
    }

    @Test
    void a_second_reversal_of_one_transaction_is_refused_even_with_a_fresh_approval() {
        // The layer above single-use: the target check fires first, so a spent approval is never
        // even the reason. Both controls hold independently, which is the point of having two.
        TransferResult original = aCompletedTransfer("rev-reuse-path");
        reversals.reverse(
                tenantId, original.transactionId(), anApprovalFor(original), "rev-key-4a", "user:tobi");

        assertThat(
                        catchThrowable(
                                () ->
                                        reversals.reverse(
                                                tenantId, original.transactionId(), anApprovalFor(original),
                                                "rev-key-4b", "user:tobi")))
                .isInstanceOf(ReversalService.NotReversible.class);
    }

    @Test
    void an_approval_is_bound_to_its_target() {
        // An approval for transaction A must not reverse transaction B, however similar.
        TransferResult a = aCompletedTransfer("rev-bound-a");
        TransferResult b = aCompletedTransfer("rev-bound-b");
        UUID approvalForA = anApprovalFor(a);

        assertThat(
                        catchThrowable(
                                () ->
                                        reversals.reverse(
                                                tenantId, b.transactionId(), approvalForA, "rev-key-5", "user:tobi")))
                .isInstanceOf(ApprovalRecords.ApprovalRejected.class);
    }

    @Test
    void an_approval_is_bound_to_its_amount() {
        TransferResult original = aCompletedTransfer("rev-amount");
        UUID wrongAmount =
                approvals.raise(tenantId, original.transactionId(), original.amountMinor() + 1, "user:ada");
        approvals.check(tenantId, wrongAmount, true, "user:tobi");

        assertThat(
                        catchThrowable(
                                () ->
                                        reversals.reverse(
                                                tenantId, original.transactionId(), wrongAmount,
                                                "rev-key-6", "user:tobi")))
                .isInstanceOf(ApprovalRecords.ApprovalRejected.class);
    }

    // ------------------------------------------------------------- not reversible

    @Test
    void a_reversal_cannot_itself_be_reversed() {
        TransferResult original = aCompletedTransfer("rev-double");
        TransferResult reversal =
                reversals.reverse(
                        tenantId, original.transactionId(), anApprovalFor(original), "rev-key-7", "user:tobi");

        assertThat(
                        catchThrowable(
                                () ->
                                        reversals.reverse(
                                                tenantId, reversal.transactionId(), UUID.randomUUID(),
                                                "rev-key-8", "user:tobi")))
                .isInstanceOf(ReversalService.NotReversible.class);
    }

    @Test
    void a_transaction_already_reversed_cannot_be_reversed_again() {
        // Even with a second, perfectly valid approval. The first reversal settles it.
        TransferResult original = aCompletedTransfer("rev-twice");
        reversals.reverse(
                tenantId, original.transactionId(), anApprovalFor(original), "rev-key-9", "user:tobi");

        UUID second = anApprovalFor(original);
        assertThat(
                        catchThrowable(
                                () ->
                                        reversals.reverse(
                                                tenantId, original.transactionId(), second,
                                                "rev-key-10", "user:tobi")))
                .isInstanceOf(ReversalService.NotReversible.class);
    }

    @Test
    void a_failed_transaction_cannot_be_reversed() {
        ledgerStatus.set(422);
        ledgerBody.set("{\"code\":\"INSUFFICIENT_FUNDS\"}");
        catchThrowableOfType(TransferService.TransferRefused.class, () -> aCompletedTransfer("rev-failed"));

        // Scoped by tenant: the key is unique per tenant, not globally, and the worker role sees
        // every tenant — so earlier runs' rows would otherwise match too.
        UUID sagaId =
                workerDb.queryForObject(
                        "SELECT id FROM orchestration.sagas"
                                + " WHERE tenant_id = ? AND channel_idempotency_key = 'rev-failed'",
                        UUID.class, tenantId);

        assertThat(
                        catchThrowable(
                                () -> reversals.reverse(tenantId, sagaId, UUID.randomUUID(), "rev-key-11", "user:tobi")))
                .isInstanceOf(ReversalService.NotReversible.class);
    }

    @Test
    void replaying_a_reversal_key_returns_the_original_reversal() {
        TransferResult original = aCompletedTransfer("rev-replay");
        UUID approvalId = anApprovalFor(original);

        TransferResult first =
                reversals.reverse(tenantId, original.transactionId(), approvalId, "rev-key-12", "user:tobi");
        TransferResult second =
                reversals.reverse(tenantId, original.transactionId(), approvalId, "rev-key-12", "user:tobi");

        // The replay never spends the approval a second time, and never raises a second reversal.
        assertThat(second.transactionId()).isEqualTo(first.transactionId());
    }
}
