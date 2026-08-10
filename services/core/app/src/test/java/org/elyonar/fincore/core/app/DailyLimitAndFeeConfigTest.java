package org.elyonar.fincore.core.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.elyonar.fincore.core.orchestration.api.ErrorCode;
import org.elyonar.fincore.core.orchestration.api.ErrorReason;
import org.elyonar.fincore.core.orchestration.api.TransferCommand;
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
 * The daily limit is enforced against the day's reservations, and the fee account is pricing.
 *
 * <p>Both were documented and not true. {@code limit_rules} accepted DAILY rows nothing ever
 * evaluated — the reservation was written and never read back, so the race the design describes
 * (two transfers each passing a here-and-now check) was not actually prevented. And the
 * fee-income account came from the request body, unvalidated, so a caller could route the
 * tenant's fee revenue to any account it could name.
 */
@SpringBootTest
class DailyLimitAndFeeConfigTest {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    @Autowired private TenantRegistry tenantRegistry;
    @Autowired private TransferService transfers;
    @Autowired private JdbcTemplate orchestrationDb;
    @Autowired @Qualifier("customerJdbcTemplate") private JdbcTemplate customerDb;
    @Autowired @Qualifier("productJdbcTemplate") private JdbcTemplate productDb;
    @Autowired @Qualifier("customerTransactionManager") private PlatformTransactionManager customerTx;
    @Autowired @Qualifier("productTransactionManager") private PlatformTransactionManager productTx;
    @Autowired @Qualifier("orchestrationTransactionManager") private PlatformTransactionManager orchestrationTx;

    private static HttpServer ledger;
    private static final AtomicReference<String> lastRequest = new AtomicReference<>();

    private UUID tenantId;
    private UUID customerId;
    private UUID fromAccount;
    private UUID configuredFeeAccount;

    @BeforeAll
    static void startLedger() throws IOException {
        ledger = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ledger.createContext(
                "/",
                exchange -> {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    // Capture postings only: an empty-bodied request (a read, a probe) must not
                    // clobber the posting this suite is about to assert against.
                    if ("POST".equals(exchange.getRequestMethod()) && !body.isEmpty()) {
                        lastRequest.set(body);
                    }
                    byte[] bytes =
                            ("{\"transactionId\":\"" + UUID.randomUUID() + "\"}").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(201, bytes.length);
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
        // Anchors this class to its own application context and stub ledger; see ReconciliationTest.
        registry.add("fincore.test.context", () -> "daily-limit-and-fee");
    }

    @BeforeEach
    void seed() {
        tenantId = UUID.randomUUID();
        tenantRegistry.register(tenantId, "test tenant", "test");
        customerId = UUID.randomUUID();
        fromAccount = UUID.randomUUID();
        configuredFeeAccount = UUID.randomUUID();

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
                                            + " VALUES (?,?, 'TIER_2', 'API', 'PER_TXN', 100000, 'NGN')",
                                    tenantId, versionId);
                            productDb.update(
                                    "INSERT INTO product.limit_rules (tenant_id, product_version_id, kyc_tier,"
                                            + " channel, limit_type, max_amount_minor, currency)"
                                            + " VALUES (?,?, 'TIER_2', 'API', 'DAILY', 150000, 'NGN')",
                                    tenantId, versionId);
                            // ₦50 flat, credited to the account the *product* names.
                            productDb.update(
                                    "INSERT INTO product.fee_rules (tenant_id, product_version_id, operation,"
                                            + " kind, flat_minor, currency, fee_account_id)"
                                            + " VALUES (?,?, 'TRANSFER', 'FLAT', 5000, 'NGN', ?)",
                                    tenantId, versionId, configuredFeeAccount);
                            // Published last, because pricing for a live version is immutable (V7):
                            // a rule added after publish would change what an already-decided transaction
                            // was priced under, and the database refuses it.
                            productDb.update(
                                    "UPDATE product.product_versions SET status = 'PUBLISHED',"
                                            + " published_by = 'user:publisher' WHERE tenant_id = ? AND id = ?",
                                    tenantId, versionId);
                        });
    }

    private TransferCommand command(String key, long amountMinor, UUID callerFeeAccount) {
        return new TransferCommand(
                tenantId,
                key,
                "fp-" + key,
                customerId,
                fromAccount,
                UUID.randomUUID(),
                callerFeeAccount,
                amountMinor,
                "NGN",
                "P",
                "API",
                "test",
                "user:ada",
                "core",
                LAGOS);
    }

    @Test
    void the_day_accumulates_and_the_daily_limit_refuses_the_breach() {
        // 90k + 50 fee reserved; the day holds 150k, so the identical second transfer must refuse
        // — the amount alone passes PER_TXN, which is exactly the case only reservations catch.
        transfers.transfer(command("day-1", 90_000, null));

        TransferService.TransferRefused refused =
                catchThrowableOfType(
                        TransferService.TransferRefused.class,
                        () -> transfers.transfer(command("day-2", 90_000, null)));

        assertThat(refused.errorCode()).isEqualTo(ErrorCode.LIMIT_EXCEEDED);
        assertThat(refused.reason()).isEqualTo(ErrorReason.DAILY_LIMIT);

        // The breach rolled back whole: no saga, no reservation, no event survives it.
        new TransactionTemplate(orchestrationTx)
                .executeWithoutResult(
                        s -> {
                            orchestrationDb.queryForObject(
                                    "SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
                            assertThat(
                                            orchestrationDb.queryForObject(
                                                    "SELECT count(*) FROM orchestration.sagas"
                                                            + " WHERE channel_idempotency_key = 'day-2'",
                                                    Long.class))
                                    .isZero();
                            assertThat(
                                            orchestrationDb.queryForObject(
                                                    "SELECT count(*) FROM orchestration.limit_reservations",
                                                    Long.class))
                                    .isEqualTo(1);
                        });
    }

    @Test
    void a_refused_day_still_accepts_a_smaller_amount() {
        transfers.transfer(command("fit-1", 90_000, null));
        // 90_050 reserved of 150_000; 50k + 50 fee still fits.
        assertThat(transfers.transfer(command("fit-2", 50_000, null)).state()).isEqualTo("COMPLETED");
    }

    @Test
    void the_fee_credits_the_account_the_product_names_not_the_callers() {
        UUID callerAccount = UUID.randomUUID();
        transfers.transfer(command("fee-1", 10_000, callerAccount));

        // The posting the Ledger received credits the configured fee-income account.
        assertThat(lastRequest.get()).contains(configuredFeeAccount.toString());
        assertThat(lastRequest.get()).doesNotContain(callerAccount.toString());

        // And the saga row records the account that was actually used, so a worker retry
        // rebuilds the identical posting.
        new TransactionTemplate(orchestrationTx)
                .executeWithoutResult(
                        s -> {
                            orchestrationDb.queryForObject(
                                    "SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
                            assertThat(
                                            orchestrationDb.queryForObject(
                                                    "SELECT fee_account_id FROM orchestration.sagas"
                                                            + " WHERE channel_idempotency_key = 'fee-1'",
                                                    UUID.class))
                                    .isEqualTo(configuredFeeAccount);
                        });
    }
}
