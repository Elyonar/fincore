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
import org.elyonar.fincore.core.orchestration.api.CashCommand;
import org.elyonar.fincore.core.orchestration.api.TransferResult;
import org.elyonar.fincore.core.orchestration.internal.saga.CashService;
import org.elyonar.fincore.core.orchestration.internal.saga.TillRecords;
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
 * Cash over the counter.
 *
 * <p>The property worth checking hardest is the entry <em>shape</em>. A deposit and a withdrawal
 * both balance whichever way round the till and the customer are, so a system that had them
 * reversed would pass every invariant and be wrong in every till. These assert the actual entries
 * the Ledger receives, not just that the saga completed.
 */
@SpringBootTest
@Import(FakeServices.class)
class CashTest {

    // Every tenant a test uses must be registered, because Core now refuses one it has
    // never heard of. Registering here rather than weakening the gate for tests: a guard
    // switched off under test is a guard nobody has tested.
    @Autowired private TenantRegistry tenantRegistry;
    @Autowired private FakeServices.FakeCustomers customers;
    @Autowired private FakeServices.FakePricing pricing;

    private static HttpServer ledger;
    private static final AtomicInteger ledgerStatus = new AtomicInteger(201);
    private static final AtomicReference<String> ledgerBody = new AtomicReference<>("{}");
    private static final AtomicReference<String> lastRequest = new AtomicReference<>();

    @Autowired private CashService cash;
    @Autowired private TillRecords tills;

    private UUID tenantId;
    private UUID customerId;
    private UUID customerAccount;
    private UUID tillAccount;
    private UUID tillId;
    private UUID feeAccount;

    @BeforeAll
    static void startLedger() throws IOException {
        ledger = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ledger.createContext(
                "/",
                exchange -> {
                    lastRequest.set(
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
        customerAccount = UUID.randomUUID();
        tillAccount = UUID.randomUUID();
        feeAccount = UUID.randomUUID();
        ledgerStatus.set(201);
        ledgerBody.set("{\"transactionId\":\"" + UUID.randomUUID() + "\"}");

        // Customer and Product are deployables now (ADR 0020): the premise these blocks
        // built in SQL is stated directly, and the assertions below are unchanged.
        customers.clear();
        customers.eligible(customerId, "TIER_2").holds(customerId, customerAccount, "P", "NGN");
        pricing.permits(5000, feeAccount, 5000000);

        tillId = tills.open(tenantId, "BR-01", null, tillAccount, "NGN", "user:teller-1");
    }

    private TransferResult cash(CashCommand.Operation operation, long amountMinor, String key) {
        return cash.execute(
                new CashCommand(
                        tenantId, operation, key, "fp-" + key, customerId, customerAccount, tillId,
                        feeAccount, amountMinor, "NGN", "P", "TELLER", "d", "user:teller-1", "core",
                        ZoneId.of("Africa/Lagos")));
    }

    /** Asserts a specific entry reached the Ledger, by account, direction and amount. */
    private void assertEntry(UUID accountId, String direction, long amountMinor) {
        assertThat(lastRequest.get())
                .contains("\"accountId\":\"" + accountId + "\"")
                .contains("\"direction\":\"" + direction + "\"")
                .contains("\"amountMinor\":\"" + amountMinor + "\"");
    }

    // ------------------------------------------------------------------ deposit

    @Test
    void a_deposit_debits_the_till_and_credits_the_customer_net_of_the_fee() {
        // ₦1,000 in, ₦50 fee: the till takes all the notes, the customer is credited ₦950, and the
        // fee account takes ₦50. Debits 100000 = credits 95000 + 5000.
        TransferResult result = cash(CashCommand.Operation.DEPOSIT, 100_000, "cash-dep");

        assertThat(result.state()).isEqualTo("COMPLETED");
        assertEntry(tillAccount, "DEBIT", 100_000);
        assertEntry(customerAccount, "CREDIT", 95_000);
        assertEntry(feeAccount, "CREDIT", 5_000);
    }

    @Test
    void the_customer_never_appears_on_both_sides_of_a_deposit() {
        // Netting the fee out of the credit rather than posting it as a separate debit is what
        // avoids this. A customer credited the principal and debited the fee would be a wash
        // transaction, and the Ledger refuses those outright.
        cash(CashCommand.Operation.DEPOSIT, 100_000, "cash-wash");

        String request = lastRequest.get();
        int customerMentions = request.split("\"accountId\":\"" + customerAccount + "\"", -1).length - 1;
        assertThat(customerMentions).isEqualTo(1);
    }

    // --------------------------------------------------------------- withdrawal

    /**
     * ₦1,000 out, ₦50 fee: the customer is debited ₦1,000 and, separately, ₦50; the till pays out
     * ₦1,000 and the fee account takes ₦50.
     *
     * <p>Two debits rather than one of ₦1,050, so the charge is a line of its own on the customer's
     * statement instead of arithmetic they have to do themselves. Legal here — and not on a deposit
     * — because both of the customer's entries are debits, so they never appear on both sides of
     * one transaction, which the Ledger refuses as a wash.
     */
    @Test
    void a_withdrawal_debits_the_customer_separately_for_the_amount_and_the_fee() {
        TransferResult result = cash(CashCommand.Operation.WITHDRAWAL, 100_000, "cash-wd");

        assertThat(result.state()).isEqualTo("COMPLETED");
        assertEntry(customerAccount, "DEBIT", 100_000);
        assertEntry(customerAccount, "DEBIT", 5_000);
        assertEntry(tillAccount, "CREDIT", 100_000);
        assertEntry(feeAccount, "CREDIT", 5_000);
    }

    /**
     * A deposit's fee is netted, and this records why rather than treating it as the natural shape.
     *
     * <p>Crediting the customer the principal and debiting them the fee would put one account on
     * both sides of one transaction, which the Ledger rejects outright. So the charge does not
     * appear on a deposit statement, and cannot until a deposit posts two ledger transactions — the
     * deposit and the charge — under one saga. Asserted so that whoever builds that finds a test
     * telling them what changes rather than one quietly agreeing with either answer.
     */
    @Test
    void a_deposit_fee_is_still_netted_because_the_ledger_refuses_a_wash() {
        cash(CashCommand.Operation.DEPOSIT, 100_000, "cash-dep-netted");

        String request = lastRequest.get();
        int customerMentions = request.split("\"accountId\":\"" + customerAccount + "\"", -1).length - 1;
        assertThat(customerMentions).isEqualTo(1);
        assertEntry(customerAccount, "CREDIT", 95_000);
    }

    // ------------------------------------------------------------------- tills

    @Test
    void cash_cannot_move_through_a_closed_till() {
        tills.close(tenantId, tillId);

        assertThat(
                        catchThrowableOfType(
                                        TransferService.TransferRefused.class,
                                        () -> cash(CashCommand.Operation.DEPOSIT, 100_000, "cash-closed"))
                                .code())
                .isEqualTo("TILL_NOT_OPEN");
    }

    @Test
    void a_till_in_another_currency_is_refused() {
        UUID usdTill = tills.open(tenantId, "BR-01", null, UUID.randomUUID(), "USD", "user:teller-1");

        assertThat(
                        catchThrowableOfType(
                                        TransferService.TransferRefused.class,
                                        () ->
                                                cash.execute(
                                                        new CashCommand(
                                                                tenantId, CashCommand.Operation.DEPOSIT, "cash-fx",
                                                                "fp", customerId, customerAccount, usdTill,
                                                                feeAccount, 100_000, "NGN", "P", "TELLER", "d",
                                                                "user:teller-1", "core", ZoneId.of("Africa/Lagos"))))
                                .code())
                .isEqualTo("CURRENCY_MISMATCH");
    }

    // ----------------------------------------------------------------- the rest

    @Test
    void a_fee_that_would_swallow_the_deposit_is_a_misconfiguration_not_a_transaction() {
        // The customer would be credited nothing. Better to say so than to fail downstream on a
        // zero-amount entry the Ledger would reject for an unrelated-looking reason.
        assertThat(
                        catchThrowableOfType(
                                        TransferService.TransferRefused.class,
                                        () -> cash(CashCommand.Operation.DEPOSIT, 5_000, "cash-allfee"))
                                .code())
                .isEqualTo("FEE_EXCEEDS_DEPOSIT");
    }

    @Test
    void an_unknown_outcome_leaves_the_cash_saga_recoverable() {
        ledgerStatus.set(500);
        ledgerBody.set("{}");

        var unknown =
                catchThrowableOfType(
                        TransferService.OutcomeUnknown.class,
                        () -> cash(CashCommand.Operation.DEPOSIT, 100_000, "cash-unknown"));

        assertThat(unknown.transactionId()).isNotNull();
    }

    @Test
    void replaying_a_cash_key_returns_the_original_result() {
        TransferResult first = cash(CashCommand.Operation.DEPOSIT, 100_000, "cash-replay");
        TransferResult second = cash(CashCommand.Operation.DEPOSIT, 100_000, "cash-replay");

        assertThat(second.transactionId()).isEqualTo(first.transactionId());
    }
}
