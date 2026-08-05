package org.elyonar.fincore.ledger.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.elyonar.fincore.ledger.posting.EntryLine.Direction.CREDIT;
import static org.elyonar.fincore.ledger.posting.EntryLine.Direction.DEBIT;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.elyonar.fincore.ledger.shared.ErrorCode;
import org.elyonar.fincore.ledger.shared.LedgerException;
import org.elyonar.fincore.ledger.support.LedgerPostgresTest;
import org.elyonar.fincore.ledger.support.TenantSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("reversal — undo by mirroring, never by editing")
class ReversalServiceTest extends LedgerPostgresTest {

    @Autowired PostingService posting;
    @Autowired ReversalService reversals;
    @Autowired DataSource dataSource;

    private UUID tenant;
    private UUID customer;
    private UUID settlement;
    private TenantSession db;

    @BeforeEach
    void seed() {
        tenant = UUID.randomUUID();
        customer = UUID.randomUUID();
        settlement = UUID.randomUUID();
        db = TenantSession.open(dataSource, tenant);
        db.execute("INSERT INTO currencies VALUES ('NGN',2,'Naira') ON CONFLICT (code) DO NOTHING");
        account(customer, "CUSTOMER", false);
        account(settlement, "SETTLEMENT_MIRROR", true);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private void account(UUID id, String type, boolean allowNegative) {
        db.execute(
                """
                INSERT INTO accounts (id, tenant_id, idempotency_key, type, currency, allow_negative)
                VALUES (?,?,?,?, 'NGN', ?)
                """,
                id, tenant, "acct-" + id, type, allowNegative);
        db.execute("INSERT INTO balances (account_id, tenant_id) VALUES (?,?)", id, tenant);
    }

    private UUID credit(String key, long amount) {
        return posting.post(
                        new PostTransactionCommand(
                                tenant, key, "user:ada", "svc:test", "credit",
                                List.of(
                                        new EntryLine(settlement, DEBIT, amount, "NGN", null),
                                        new EntryLine(customer, CREDIT, amount, "NGN", null))))
                .transactionId();
    }

    private ReverseTransactionCommand reversalOf(UUID original, String key) {
        return new ReverseTransactionCommand(tenant, original, key, "user:ops", "svc:test");
    }

    private long balance(UUID account) {
        return db.count("SELECT current_minor FROM balances WHERE account_id = ?", account);
    }

    @Test
    @DisplayName("a reversal mirrors the original and returns the balance")
    void reversal_undoes_the_movement() {
        UUID original = credit("tx-1", 500_00);
        assertThat(balance(customer)).isEqualTo(500_00);

        UUID reversal = reversals.reverse(reversalOf(original, "rev-1")).transactionId();

        assertThat(balance(customer)).isZero();
        assertThat(balance(settlement)).isZero();
        assertThat(db.count("SELECT count(*) FROM entries WHERE transaction_id = ?", reversal)).isEqualTo(2);
        assertThat(db.count("SELECT count(*) FROM entries WHERE transaction_id = ?", original))
                .as("the original entries are untouched: undo is a new record, not an edit")
                .isEqualTo(2);
        assertThat(db.count("SELECT count(*) FROM ledger_transactions WHERE id=? AND status='REVERSED'", original))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("mirrored entries carry today's business date, not the original's")
    void reversal_does_not_post_into_the_past() {
        // Backdated at posting time. It cannot be backdated afterwards — the append-only trigger
        // refuses, which is exactly the protection being relied on here.
        LocalDate backdated = LocalDate.now().minusDays(20);
        UUID original =
                posting.post(
                                new PostTransactionCommand(
                                        tenant, "tx-backdated", "user:ada", "svc:test", "backdated credit",
                                        List.of(
                                                new EntryLine(settlement, DEBIT, 500_00, "NGN", backdated),
                                                new EntryLine(customer, CREDIT, 500_00, "NGN", backdated)),
                                        null,
                                        null,
                                        "late settlement file"))
                        .transactionId();

        UUID reversal = reversals.reverse(reversalOf(original, "rev-1")).transactionId();

        assertThat(
                        db.count(
                                "SELECT count(*) FROM entries WHERE transaction_id = ? AND value_date = ?",
                                original,
                                backdated))
                .as("the original really was backdated")
                .isEqualTo(2);

        assertThat(
                        db.count(
                                "SELECT count(*) FROM entries WHERE transaction_id = ? AND value_date = ?",
                                reversal,
                                LocalDate.now()))
                .as("posting into the past would rewrite a period that may be closed and signed off")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("an original can be reversed only once, and the loser learns the winner's id")
    void reversal_is_exactly_once() {
        UUID original = credit("tx-1", 500_00);
        UUID first = reversals.reverse(reversalOf(original, "rev-1")).transactionId();

        assertThatThrownBy(() -> reversals.reverse(reversalOf(original, "rev-2")))
                .isInstanceOf(ReversalService.AlreadyReversedException.class)
                .satisfies(
                        e ->
                                assertThat(((ReversalService.AlreadyReversedException) e).reversalId())
                                        .as("a saga must be able to converge on the winner, not retry forever")
                                        .isEqualTo(first));

        assertThat(balance(customer)).as("the money is undone once, not twice").isZero();
    }

    @Test
    @DisplayName("retrying a reversal with the same key replays it")
    void reversal_is_idempotent() {
        UUID original = credit("tx-1", 500_00);
        PostingResult first = reversals.reverse(reversalOf(original, "rev-dup"));
        PostingResult second = reversals.reverse(reversalOf(original, "rev-dup"));

        assertThat(second.transactionId()).isEqualTo(first.transactionId());
        assertThat(second.replayed()).isTrue();
        assertThat(balance(customer)).isZero();
    }

    @Test
    @DisplayName("a reversal cannot itself be reversed")
    void no_reversal_of_reversal() {
        UUID original = credit("tx-1", 500_00);
        UUID reversal = reversals.reverse(reversalOf(original, "rev-1")).transactionId();

        assertThatThrownBy(() -> reversals.reverse(reversalOf(reversal, "rev-2")))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).errorCode())
                .as("reversing a reversal resurrects money while every status reads terminal")
                .isEqualTo(ErrorCode.REVERSAL_OF_REVERSAL);
    }

    @Test
    @DisplayName("reversal may drive a guarded account negative — undo must always be possible")
    void reversal_bypasses_the_negative_guard() {
        UUID original = credit("tx-1", 500_00);
        // The customer spends the money onward before the original is disputed.
        posting.post(
                new PostTransactionCommand(
                        tenant, "spend", "u", "s", "onward",
                        List.of(
                                new EntryLine(customer, DEBIT, 500_00, "NGN", null),
                                new EntryLine(settlement, CREDIT, 500_00, "NGN", null))));
        assertThat(balance(customer)).isZero();

        reversals.reverse(reversalOf(original, "rev-1"));

        assertThat(balance(customer))
                .as("the erroneous-credit dispute path: authorized exposure, not a bug")
                .isEqualTo(-500_00);
    }

    @Test
    @DisplayName("reversal may post into an account closed since")
    void reversal_bypasses_the_closed_check() {
        UUID original = credit("tx-1", 500_00);
        posting.post(
                new PostTransactionCommand(
                        tenant, "sweep", "u", "s", "sweep to zero",
                        List.of(
                                new EntryLine(customer, DEBIT, 500_00, "NGN", null),
                                new EntryLine(settlement, CREDIT, 500_00, "NGN", null))));
        db.execute(
                "UPDATE accounts SET status='CLOSED', closed_by='user:ops', closed_at=now() WHERE id=?", customer);

        reversals.reverse(reversalOf(original, "rev-1"));

        assertThat(balance(customer)).isEqualTo(-500_00);
        assertThat(db.count("SELECT count(*) FROM accounts WHERE id=? AND status='CLOSED'", customer))
                .as("the account stays closed throughout")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an original with compensations cannot be plainly reversed")
    void compensated_originals_reject_reversal() {
        UUID original = credit("tx-1", 500_00);

        // A partial refund already given against the original.
        posting.post(
                new PostTransactionCommand(
                        tenant, "comp-1", "u", "s", "partial refund",
                        List.of(
                                new EntryLine(customer, DEBIT, 200_00, "NGN", null),
                                new EntryLine(settlement, CREDIT, 200_00, "NGN", null)),
                        null,
                        original));

        assertThatThrownBy(() -> reversals.reverse(reversalOf(original, "rev-1")))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).errorCode())
                .as("partial refund plus full reversal would credit the customer twice")
                .isEqualTo(ErrorCode.HAS_COMPENSATIONS);
    }

    @Test
    @DisplayName("a reversed original cannot then be compensated — the same double credit, swapped")
    void reversed_originals_reject_compensation() {
        UUID original = credit("tx-1", 500_00);
        reversals.reverse(reversalOf(original, "rev-1"));

        var lateCompensation =
                new PostTransactionCommand(
                        tenant, "comp-late", "u", "s", "partial refund",
                        List.of(
                                new EntryLine(customer, DEBIT, 200_00, "NGN", null),
                                new EntryLine(settlement, CREDIT, 200_00, "NGN", null)),
                        null,
                        original);

        assertThatThrownBy(() -> posting.post(lateCompensation))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).errorCode())
                .isEqualTo(ErrorCode.TARGET_REVERSED);

        assertThat(balance(customer)).as("the exclusion holds in both temporal orders").isZero();
    }
}
