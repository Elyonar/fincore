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

    // The owner datasource (primary; the one migrations run as), used to install and remove the
    // injected failure — no restricted role may create triggers, which is as it should be.
    @Autowired private javax.sql.DataSource ownerDataSource;
    @Autowired @Qualifier("workerJdbcTemplate") private JdbcTemplate workerDb;
    @Autowired @Qualifier("orchestrationJdbcTemplate") private JdbcTemplate orchestrationDb;
    @Autowired @Qualifier("orchestrationTransactionManager")
    private PlatformTransactionManager orchestrationTx;
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
                                            + " ledger_account_id, currency, product_code) VALUES (?,?,?, 'NGN', 'P')",
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

    /**
     * A deposit's debits total the principal alone, and that is not a mismatch.
     *
     * <p>The reconciler used to expect principal + fee of every posting. A withdrawal and a transfer
     * debit the customer for both, so that held; a deposit debits only the till, which hands over
     * the gross notes, while the fee comes out of the credit side. So every deposit that charged a
     * fee was flagged — correct postings, opening cases, in a queue nothing rendered.
     */
    @Test
    void a_deposit_whose_debits_total_the_principal_alone_is_not_a_mismatch() {
        UUID sagaId = UUID.randomUUID();
        UUID ledgerTransactionId = UUID.randomUUID();
        completedSagaOfType(sagaId, ledgerTransactionId, "DEPOSIT", 100_000, 5_000);
        readTarget.set(ledgerTransactionId);
        // The till's single debit — the fee reached income out of the credit side.
        ledgerAnswers(ledgerTransactionId, 100_000);

        assertThat(reconciliation.run()).isZero();
        assertThat(count("reconciliation_findings", "AMOUNT_MISMATCH")).isZero();
    }

    /** The mirror: a withdrawal's debits must still total principal + fee. */
    @Test
    void a_withdrawal_whose_debits_omit_the_fee_is_a_mismatch() {
        UUID sagaId = UUID.randomUUID();
        UUID ledgerTransactionId = UUID.randomUUID();
        completedSagaOfType(sagaId, ledgerTransactionId, "WITHDRAWAL", 100_000, 5_000);
        readTarget.set(ledgerTransactionId);
        ledgerAnswers(ledgerTransactionId, 100_000);

        assertThat(reconciliation.run()).isEqualTo(1);
        assertThat(count("reconciliation_findings", "AMOUNT_MISMATCH")).isEqualTo(1);
    }

    /** A terminal saga of a given type, written straight in — the type is the whole subject here. */
    private void completedSagaOfType(
            UUID sagaId, UUID ledgerTransactionId, String type, long amountMinor, long feeMinor) {
        // The orchestration role, not the worker's: the worker may read sagas and never write them,
        // which is the separation working rather than an obstacle. Inside a transaction because the
        // tenant context is SET LOCAL and row-level security refuses a write without one.
        new org.springframework.transaction.support.TransactionTemplate(orchestrationTx)
                .executeWithoutResult(status -> {
                    orchestrationDb.queryForObject(
                            "SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
                    orchestrationDb.update(
                """
                INSERT INTO orchestration.sagas
                    (id, tenant_id, type, state, channel_idempotency_key, request_fingerprint,
                     amount_minor, fee_minor, currency, initiated_by, executed_by,
                     from_account_id, to_account_id, ledger_transaction_id, terminal_at)
                VALUES (?, ?, ?, 'COMPLETED', ?, 'fp', ?, ?, 'NGN', 'user:ada', 'core',
                        gen_random_uuid(), gen_random_uuid(), ?, now())
                """,
                            sagaId,
                            tenantId,
                            type,
                            "rec-type-" + sagaId,
                            amountMinor,
                            feeMinor,
                            ledgerTransactionId);
                });
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

    /**
     * The finding and its ops case are one write: both rows or neither.
     *
     * <p>They used to commit separately — {@code record} was {@code protected @Transactional} and
     * self-invoked, so the annotation never applied — and a crash between the two inserts left a
     * finding the duplicate guard then suppressed on every later run: a mismatch recorded forever
     * in a table no operator queue surfaced. The injected failure here is a trigger on the second
     * insert, which is exactly the crash window.
     */
    @Test
    void a_failure_between_the_two_inserts_leaves_both_rows_or_neither() {
        completeTransfer(50_000);
        readStatus.set(404);
        readBody.set("{\"code\":\"TRANSACTION_NOT_FOUND\"}");

        // The owner installs the failure: any ops-case insert for this tenant raises.
        org.springframework.jdbc.core.JdbcTemplate owner =
                new org.springframework.jdbc.core.JdbcTemplate(ownerDataSource);
        owner.execute(
                """
                CREATE OR REPLACE FUNCTION orchestration.fail_ops_case_insert() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'injected failure between the two inserts'; END
                $$ LANGUAGE plpgsql
                """);
        owner.execute(
                ("CREATE TRIGGER inject_ops_case_failure BEFORE INSERT ON orchestration.ops_cases"
                                + " FOR EACH ROW WHEN (NEW.tenant_id = '%s'::uuid)"
                                + " EXECUTE FUNCTION orchestration.fail_ops_case_insert()")
                        .formatted(tenantId));
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> reconciliation.run())
                    .isInstanceOf(RuntimeException.class);

            // Neither row: the finding insert rolled back with its case, so nothing is orphaned.
            assertThat(count("reconciliation_findings", "LEDGER_MISSING")).isZero();
            assertThat(count("ops_cases", "RECONCILIATION_MISMATCH")).isZero();
        } finally {
            owner.execute("DROP TRIGGER inject_ops_case_failure ON orchestration.ops_cases");
            owner.execute("DROP FUNCTION orchestration.fail_ops_case_insert()");
        }

        // And because neither row survived, the next run records the pair whole — the crash cost
        // a delay, not the finding.
        assertThat(reconciliation.run()).isEqualTo(1);
        assertThat(count("reconciliation_findings", "LEDGER_MISSING")).isEqualTo(1);
        assertThat(count("ops_cases", "RECONCILIATION_MISMATCH")).isEqualTo(1);
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
