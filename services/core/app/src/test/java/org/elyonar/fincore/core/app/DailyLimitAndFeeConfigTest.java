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
import org.springframework.context.annotation.Import;
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
@Import(FakeServices.class)
class DailyLimitAndFeeConfigTest {

    /*
     * Two currency cases left with ADR 0020. "An operation priced in another currency refuses
     * rather than pricing free" and its free-in-any-currency counterpart are statements about the
     * *evaluator* — which currency a rule applies in, and what an absent rule means — and the
     * evaluator is the Product service's now. Asserting them here would mean teaching this suite's
     * double to reimplement them, which proves only that the double agrees with itself.
     *
     * What remains is Core's: the daily reservation, taken in the same transaction as the saga, and
     * the fee landing in the account the product named rather than one the caller asked for.
     */

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    @Autowired private TenantRegistry tenantRegistry;
    @Autowired private FakeServices.FakeCustomers customers;
    @Autowired private FakeServices.FakePricing pricing;
    @Autowired private TransferService transfers;
    @Autowired private JdbcTemplate orchestrationDb;
    @Autowired @Qualifier("orchestrationTransactionManager") private PlatformTransactionManager orchestrationTx;

    private static HttpServer ledger;
    private static final AtomicReference<String> lastRequest = new AtomicReference<>();

    private UUID tenantId;
    private UUID customerId;
    private UUID fromAccount;
    private UUID configuredFeeAccount;
    private UUID usdAccount;

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
        usdAccount = UUID.randomUUID();

        // The rules these blocks wrote are the Product service's now (ADR 0020). What this suite
        // asserts is what the money path does with a decision, so the decision is stated directly
        // and each test narrows it where it needs to.
        customers.clear();
        customers.eligible(customerId, "TIER_2")
                .holds(customerId, fromAccount, "P", "NGN")
                .holds(customerId, usdAccount, "Q", "USD");

        // The numbers the rules carried: ₦1,000 flat, ₦100,000 per transaction, ₦150,000 a day.
        // The daily accumulation is what this suite is really about, and that is Core's reservation
        // rather than Product's rule — the rule only supplies the ceiling it is taken against.
        pricing.permits(5000, configuredFeeAccount, 100_000L).daily(150_000L);
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

    /** A transfer from the given account, in the given currency. */
    private TransferCommand commandFrom(String key, UUID payingAccount, long amountMinor, String currency) {
        return new TransferCommand(
                tenantId, key, "fp-" + key, customerId, payingAccount, UUID.randomUUID(), null,
                amountMinor, currency, "P", "API", "test", "user:ada", "core", LAGOS);
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
