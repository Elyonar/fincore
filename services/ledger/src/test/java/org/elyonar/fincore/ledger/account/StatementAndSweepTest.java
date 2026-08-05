package org.elyonar.fincore.ledger.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.elyonar.fincore.ledger.posting.EntryLine.Direction.CREDIT;
import static org.elyonar.fincore.ledger.posting.EntryLine.Direction.DEBIT;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.elyonar.fincore.ledger.hold.HoldExpirySweep;
import org.elyonar.fincore.ledger.hold.HoldService;
import org.elyonar.fincore.ledger.hold.PlaceHoldCommand;
import org.elyonar.fincore.ledger.period.PeriodService;
import org.elyonar.fincore.ledger.posting.EntryLine;
import org.elyonar.fincore.ledger.posting.PostTransactionCommand;
import org.elyonar.fincore.ledger.posting.PostingService;
import org.elyonar.fincore.ledger.posting.ReversalService;
import org.elyonar.fincore.ledger.posting.ReverseTransactionCommand;
import org.elyonar.fincore.ledger.shared.ErrorCode;
import org.elyonar.fincore.ledger.shared.LedgerException;
import org.elyonar.fincore.ledger.support.LedgerPostgresTest;
import org.elyonar.fincore.ledger.support.TenantSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("statements, the closed-account sweep, and hold expiry")
class StatementAndSweepTest extends LedgerPostgresTest {

    @Autowired PostingService posting;
    @Autowired ReversalService reversals;
    @Autowired StatementService statements;
    @Autowired PeriodService periods;
    @Autowired HoldService holds;
    @Autowired HoldExpirySweep expirySweep;
    @Autowired DataSource dataSource;

    private UUID tenant;
    private UUID customer;
    private UUID settlement;
    private UUID suspense;
    private TenantSession db;
    private final ZoneId lagos = ZoneId.of("Africa/Lagos");

    @BeforeEach
    void seed() {
        tenant = UUID.randomUUID();
        customer = UUID.randomUUID();
        settlement = UUID.randomUUID();
        suspense = UUID.randomUUID();
        db = TenantSession.open(dataSource, tenant);
        db.execute("INSERT INTO currencies VALUES ('NGN',2,'Naira') ON CONFLICT (code) DO NOTHING");
        account(customer, "CUSTOMER", false);
        account(settlement, "SETTLEMENT_MIRROR", true);
        account(suspense, "SUSPENSE", true);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private void account(UUID id, String type, boolean allowNegative) {
        db.execute(
                "INSERT INTO accounts (id, tenant_id, idempotency_key, type, currency, allow_negative)"
                        + " VALUES (?,?,?,?, 'NGN', ?)",
                id, tenant, "acct-" + id, type, allowNegative);
        db.execute("INSERT INTO balances (account_id, tenant_id) VALUES (?,?)", id, tenant);
    }

    private UUID credit(String key, long amount, LocalDate valueDate, String reason) {
        return posting.post(
                        new PostTransactionCommand(
                                tenant, key, "user:ada", "svc:test", "credit",
                                List.of(
                                        new EntryLine(settlement, DEBIT, amount, "NGN", valueDate),
                                        new EntryLine(customer, CREDIT, amount, "NGN", valueDate)),
                                null, null, reason))
                .transactionId();
    }

    private long balance(UUID account) {
        return db.count("SELECT current_minor FROM balances WHERE account_id = ?", account);
    }

    private void closeAccountDirectly(UUID id) {
        db.execute("UPDATE accounts SET status='CLOSED', closed_by='user:ops', closed_at=now() WHERE id=?", id);
    }

    private UUID drainAndClose() {
        UUID original = credit("tx-1", 500_00, null, null);
        posting.post(
                new PostTransactionCommand(
                        tenant, "spend", "u", "s", "onward",
                        List.of(
                                new EntryLine(customer, DEBIT, 500_00, "NGN", null),
                                new EntryLine(settlement, CREDIT, 500_00, "NGN", null))));
        closeAccountDirectly(customer);
        reversals.reverse(new ReverseTransactionCommand(tenant, original, "rev-1", "user:ops", "svc:test"));
        return original;
    }

    @Test
    @DisplayName("a statement reconciles: opening plus movements equals closing")
    void statement_reconciles() {
        LocalDate today = LocalDate.now(lagos);
        credit("tx-1", 500_00, today, null);
        credit("tx-2", 250_00, today, null);

        var statement = statements.forPeriod(tenant, customer, today, today);

        assertThat(statement.openingMinor()).isZero();
        assertThat(statement.lines()).hasSize(2);
        assertThat(statement.closingMinor())
                .as("the equality is the statement's own proof of integrity")
                .isEqualTo(750_00);
    }

    @Test
    @DisplayName("opening balance carries forward from before the period")
    void opening_balance_carries_forward() {
        LocalDate today = LocalDate.now(lagos);
        credit("tx-old", 400_00, today.minusDays(10), "prior period");

        var statement = statements.forPeriod(tenant, customer, today.minusDays(1), today);

        assertThat(statement.openingMinor()).isEqualTo(400_00);
        assertThat(statement.lines()).as("prior movement is in the opening figure, not the lines").isEmpty();
        assertThat(statement.closingMinor()).isEqualTo(400_00);
    }

    @Test
    @DisplayName("lines are in business order, and each carries both dates")
    void lines_carry_both_dates_in_business_order() {
        LocalDate today = LocalDate.now(lagos);
        credit("tx-today", 100_00, today, null);
        credit("tx-backdated", 200_00, today.minusDays(3), "late settlement file");

        var statement = statements.forPeriod(tenant, customer, today.minusDays(5), today);

        assertThat(statement.lines()).hasSize(2);
        assertThat(statement.lines().get(0).valueDate())
                .as("ordered by value date, so a backdated item sorts into business order")
                .isEqualTo(today.minusDays(3));
        assertThat(statement.lines().get(0).bookedAt())
                .as("its booking date sits beside it, so the customer can see why it is there")
                .isAfter(Instant.now().minus(1, ChronoUnit.HOURS));
    }

    @Test
    @DisplayName("a closed period is FINAL; an open one is INTERIM")
    void closed_periods_are_final() {
        LocalDate today = LocalDate.now(lagos);
        credit("tx-1", 100_00, today.minusDays(6), "late file");

        assertThat(statements.forPeriod(tenant, customer, today.minusDays(10), today.minusDays(5)).isFinal())
                .as("still open, so it may still change")
                .isFalse();

        periods.close(tenant, today.minusDays(5), "user:ops");

        assertThat(statements.forPeriod(tenant, customer, today.minusDays(10), today.minusDays(5)).isFinal())
                .as("no posting can land in a closed period, so this document is now immutable")
                .isTrue();
    }

    @Test
    @DisplayName("a final statement is unchanged when re-requested after later activity")
    void final_statements_do_not_change() {
        LocalDate today = LocalDate.now(lagos);
        credit("tx-1", 100_00, today.minusDays(6), "late file");
        periods.close(tenant, today.minusDays(5), "user:ops");

        var first = statements.forPeriod(tenant, customer, today.minusDays(10), today.minusDays(5));
        credit("tx-later", 900_00, today, null);
        var second = statements.forPeriod(tenant, customer, today.minusDays(10), today.minusDays(5));

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("reversal into a closed account leaves residue that a sweep can clear")
    void sweep_clears_negative_residue() {
        drainAndClose();
        assertThat(balance(customer)).as("the erroneous-credit dispute path leaves it negative").isEqualTo(-500_00);

        posting.post(
                new PostTransactionCommand(
                        tenant, "sweep-1", "user:ops", "svc:test", "residue sweep",
                        List.of(
                                new EntryLine(customer, CREDIT, 500_00, "NGN", null),
                                new EntryLine(suspense, DEBIT, 500_00, "NGN", null)),
                        null, null, null, true));

        assertThat(balance(customer)).as("direction-neutral: a debit-only valve would trap this case").isZero();
        assertThat(balance(suspense)).isEqualTo(-500_00);
        assertThat(db.count("SELECT count(*) FROM accounts WHERE id=? AND status='CLOSED'", customer))
                .as("the account stays closed throughout")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an ordinary posting into a closed account is still refused")
    void closed_accounts_still_reject_ordinary_postings() {
        drainAndClose();

        assertThatThrownBy(
                        () ->
                                posting.post(
                                        new PostTransactionCommand(
                                                tenant, "ordinary", "u", "s", "not a sweep",
                                                List.of(
                                                        new EntryLine(customer, CREDIT, 500_00, "NGN", null),
                                                        new EntryLine(suspense, DEBIT, 500_00, "NGN", null)))))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).errorCode())
                .isEqualTo(ErrorCode.ACCOUNT_CLOSED);
    }

    @Test
    @DisplayName("a sweep that does not zero the account is refused")
    void sweep_must_zero_the_account() {
        drainAndClose();

        assertThatThrownBy(
                        () ->
                                posting.post(
                                        new PostTransactionCommand(
                                                tenant, "sweep-partial", "u", "s", "partial",
                                                List.of(
                                                        new EntryLine(customer, CREDIT, 200_00, "NGN", null),
                                                        new EntryLine(suspense, DEBIT, 200_00, "NGN", null)),
                                                null, null, null, true)))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).errorCode())
                .isEqualTo(ErrorCode.SWEEP_INVALID);
    }

    @Test
    @DisplayName("a sweep without a suspense counterparty is refused")
    void sweep_requires_a_suspense_counterparty() {
        drainAndClose();

        assertThatThrownBy(
                        () ->
                                posting.post(
                                        new PostTransactionCommand(
                                                tenant, "sweep-wrong", "u", "s", "wrong counterparty",
                                                List.of(
                                                        new EntryLine(customer, CREDIT, 500_00, "NGN", null),
                                                        new EntryLine(settlement, DEBIT, 500_00, "NGN", null)),
                                                null, null, null, true)))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).errorCode())
                .as("the flag must not become a general way to post into closed accounts")
                .isEqualTo(ErrorCode.SWEEP_INVALID);
    }

    @Test
    @DisplayName("an expired hold returns its funds to available balance")
    void expiry_returns_reserved_funds() {
        credit("fund", 1_000_00, null, null);
        UUID hold =
                holds.place(
                        new PlaceHoldCommand(
                                tenant, "h-1", customer, 300_00, "NGN", Instant.now().plus(1, ChronoUnit.DAYS)));
        assertThat(db.count("SELECT holds_total_minor FROM balances WHERE account_id = ?", customer))
                .isEqualTo(300_00);

        db.execute("UPDATE holds SET expires_at = now() - interval '1 minute' WHERE id = ?", hold);
        assertThat(expirySweep.expireFor(tenant, 100)).isEqualTo(1);

        assertThat(db.count("SELECT holds_total_minor FROM balances WHERE account_id = ?", customer))
                .as("until this happens the customer's money is frozen, not merely mis-recorded")
                .isZero();
        assertThat(holds.find(tenant, hold).status()).isEqualTo("EXPIRED");
        assertThat(balance(customer)).as("expiry moves no money").isEqualTo(1_000_00);
    }

    @Test
    @DisplayName("expiry never touches a hold that a capture already consumed")
    void expiry_leaves_consumed_holds_alone() {
        credit("fund", 1_000_00, null, null);
        UUID hold =
                holds.place(
                        new PlaceHoldCommand(
                                tenant, "h-1", customer, 300_00, "NGN", Instant.now().plus(1, ChronoUnit.DAYS)));
        posting.post(
                new PostTransactionCommand(
                        tenant, "cap", "u", "s", "capture",
                        List.of(
                                new EntryLine(customer, DEBIT, 300_00, "NGN", null),
                                new EntryLine(settlement, CREDIT, 300_00, "NGN", null)),
                        hold));

        db.execute("UPDATE holds SET expires_at = now() - interval '1 minute' WHERE id = ?", hold);

        assertThat(expirySweep.expireFor(tenant, 100))
                .as("status under the lock is the authority, not the batch snapshot")
                .isZero();
        assertThat(holds.find(tenant, hold).status()).isEqualTo("CONSUMED");
        assertThat(balance(customer)).isEqualTo(700_00);
    }

    @Test
    @DisplayName("an unexpired hold is left alone")
    void expiry_spares_live_holds() {
        credit("fund", 1_000_00, null, null);
        holds.place(
                new PlaceHoldCommand(
                        tenant, "h-1", customer, 300_00, "NGN", Instant.now().plus(1, ChronoUnit.DAYS)));

        assertThat(expirySweep.expireFor(tenant, 100)).isZero();
        assertThat(db.count("SELECT holds_total_minor FROM balances WHERE account_id = ?", customer))
                .isEqualTo(300_00);
    }
}
