package org.elyonar.fincore.ledger.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.elyonar.fincore.ledger.posting.EntryLine.Direction.CREDIT;
import static org.elyonar.fincore.ledger.posting.EntryLine.Direction.DEBIT;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.elyonar.fincore.ledger.posting.EntryLine;
import org.elyonar.fincore.ledger.posting.PostTransactionCommand;
import org.elyonar.fincore.ledger.posting.PostingService;
import org.elyonar.fincore.ledger.shared.ErrorCode;
import org.elyonar.fincore.ledger.shared.LedgerException;
import org.elyonar.fincore.ledger.support.LedgerPostgresTest;
import org.elyonar.fincore.ledger.support.TenantSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("holds — reservations reduce available funds without moving money")
class HoldServiceTest extends LedgerPostgresTest {

    @Autowired HoldService holds;
    @Autowired PostingService posting;
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
        db.execute("INSERT INTO currencies VALUES ('USD',2,'Dollar') ON CONFLICT (code) DO NOTHING");
        account(customer, "CUSTOMER", false);
        account(settlement, "SETTLEMENT_MIRROR", true);
        posting.post(fund("seed", 1_000_00));
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

    private PostTransactionCommand fund(String key, long amount) {
        return new PostTransactionCommand(
                tenant, key, "user:ada", "svc:test", "funding",
                List.of(
                        new EntryLine(settlement, DEBIT, amount, "NGN", null),
                        new EntryLine(customer, CREDIT, amount, "NGN", null)));
    }

    private PlaceHoldCommand hold(String key, long amount) {
        return new PlaceHoldCommand(
                tenant, key, customer, amount, "NGN", Instant.now().plus(1, ChronoUnit.DAYS));
    }

    private long holdsTotal() {
        return db.count("SELECT holds_total_minor FROM balances WHERE account_id = ?", customer);
    }

    private long current() {
        return db.count("SELECT current_minor FROM balances WHERE account_id = ?", customer);
    }

    @Test
    @DisplayName("placing a hold reserves funds without writing an entry")
    void placing_reserves_without_moving_money() {
        holds.place(hold("h1", 300_00));

        assertThat(holdsTotal()).isEqualTo(300_00);
        assertThat(current()).as("a hold moves no money").isEqualTo(1_000_00);
        assertThat(db.count("SELECT count(*) FROM entries WHERE tenant_id = ?", tenant))
                .as("still only the funding pair: placing a hold writes no entry at all")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("reserved funds cannot then be spent by a posting")
    void reserved_funds_are_not_available() {
        holds.place(hold("h1", 900_00));

        var spend =
                new PostTransactionCommand(
                        tenant, "spend", "u", "s", "d",
                        List.of(
                                new EntryLine(customer, DEBIT, 200_00, "NGN", null),
                                new EntryLine(settlement, CREDIT, 200_00, "NGN", null)));

        assertThatThrownBy(() -> posting.post(spend))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).errorCode())
                .as("available is current minus holds, and 1000 - 900 < 200")
                .isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
    }

    @Test
    @DisplayName("placement is idempotent, so a retry never double-reserves")
    void placement_is_idempotent() {
        UUID first = holds.place(hold("h-dup", 300_00));
        UUID second = holds.place(hold("h-dup", 300_00));

        assertThat(second).isEqualTo(first);
        assertThat(holdsTotal()).as("a retried placement must reserve once").isEqualTo(300_00);
    }

    @Test
    @DisplayName("a hold beyond available funds is refused")
    void cannot_reserve_more_than_available() {
        assertThatThrownBy(() -> holds.place(hold("h-big", 1_500_00)))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).errorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
        assertThat(holdsTotal()).isZero();
    }

    @Test
    @DisplayName("a hold without an expiry is refused outright")
    void expiry_is_mandatory() {
        var unbounded = new PlaceHoldCommand(tenant, "h-forever", customer, 100_00, "NGN", null);
        assertThatThrownBy(() -> holds.place(unbounded))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("permanent lien");
    }

    @Test
    @DisplayName("release reports RELEASED_NOW and returns the funds")
    void release_returns_the_reservation() {
        UUID id = holds.place(hold("h1", 300_00));

        assertThat(holds.release(tenant, id)).isEqualTo(HoldReleaseOutcome.RELEASED_NOW);
        assertThat(holdsTotal()).isZero();
        assertThat(current()).isEqualTo(1_000_00);
    }

    @Test
    @DisplayName("releasing twice reports ALREADY_RELEASED, not a success-shaped no-op")
    void second_release_is_distinguishable() {
        UUID id = holds.place(hold("h1", 300_00));
        holds.release(tenant, id);

        assertThat(holds.release(tenant, id))
                .as("a caller must be able to tell 'I released it' from 'it was already gone'")
                .isEqualTo(HoldReleaseOutcome.ALREADY_RELEASED);
        assertThat(holdsTotal()).as("the reservation is returned once, not twice").isZero();
    }

    @Test
    @DisplayName("capture commits the hold and the entries in one transaction")
    void capture_is_atomic() {
        UUID id = holds.place(hold("h1", 500_00));

        var capture =
                new PostTransactionCommand(
                        tenant, "cap", "u", "s", "NIBSS settlement",
                        List.of(
                                new EntryLine(customer, DEBIT, 500_00, "NGN", null),
                                new EntryLine(settlement, CREDIT, 500_00, "NGN", null)),
                        id);

        posting.post(capture);

        assertThat(current()).isEqualTo(500_00);
        assertThat(holdsTotal()).as("the reservation is consumed, not merely reduced").isZero();
        assertThat(holds.find(tenant, id).status()).isEqualTo("CONSUMED");
    }

    @Test
    @DisplayName("a partial capture consumes the hold and releases the remainder explicitly")
    void partial_capture_is_single_shot() {
        UUID id = holds.place(hold("h1", 500_00));

        var capture =
                new PostTransactionCommand(
                        tenant, "cap", "u", "s", "d",
                        List.of(
                                new EntryLine(customer, DEBIT, 300_00, "NGN", null),
                                new EntryLine(settlement, CREDIT, 300_00, "NGN", null)),
                        id);
        posting.post(capture);

        assertThat(current()).isEqualTo(700_00);
        assertThat(holdsTotal())
                .as("the unspent 200.00 is released rather than left reserved forever")
                .isZero();
        assertThat(holds.find(tenant, id).status()).isEqualTo("CONSUMED");
    }

    @Test
    @DisplayName("capturing more than was reserved is refused")
    void cannot_capture_more_than_reserved() {
        UUID id = holds.place(hold("h1", 100_00));

        var overcapture =
                new PostTransactionCommand(
                        tenant, "cap", "u", "s", "d",
                        List.of(
                                new EntryLine(customer, DEBIT, 400_00, "NGN", null),
                                new EntryLine(settlement, CREDIT, 400_00, "NGN", null)),
                        id);

        assertThatThrownBy(() -> posting.post(overcapture))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).errorCode())
                .isEqualTo(ErrorCode.HOLD_EXCEEDED);

        assertThat(holds.find(tenant, id).status()).as("a refused capture leaves the hold ACTIVE").isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("a released hold cannot be captured")
    void cannot_capture_a_released_hold() {
        UUID id = holds.place(hold("h1", 500_00));
        holds.release(tenant, id);

        var capture =
                new PostTransactionCommand(
                        tenant, "cap", "u", "s", "d",
                        List.of(
                                new EntryLine(customer, DEBIT, 500_00, "NGN", null),
                                new EntryLine(settlement, CREDIT, 500_00, "NGN", null)),
                        id);

        assertThatThrownBy(() -> posting.post(capture))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).errorCode())
                .isEqualTo(ErrorCode.HOLD_NOT_ACTIVE);
    }

    @Test
    @DisplayName("releasing a consumed hold reports ALREADY_CONSUMED — the money moved")
    void releasing_a_captured_hold_says_so() {
        UUID id = holds.place(hold("h1", 500_00));
        posting.post(
                new PostTransactionCommand(
                        tenant, "cap", "u", "s", "d",
                        List.of(
                                new EntryLine(customer, DEBIT, 500_00, "NGN", null),
                                new EntryLine(settlement, CREDIT, 500_00, "NGN", null)),
                        id));

        assertThat(holds.release(tenant, id))
                .as("the caller must not believe it recovered funds that were already spent")
                .isEqualTo(HoldReleaseOutcome.ALREADY_CONSUMED);
        assertThat(current()).isEqualTo(500_00);
    }

    @Test
    @DisplayName("a hold on an untouched account cannot be captured by an unrelated posting")
    void hold_must_belong_to_a_touched_account() {
        UUID other = UUID.randomUUID();
        account(other, "CUSTOMER", true);
        UUID id =
                holds.place(
                        new PlaceHoldCommand(
                                tenant, "h-other", other, 100_00, "NGN", Instant.now().plus(1, ChronoUnit.DAYS)));

        var unrelated =
                new PostTransactionCommand(
                        tenant, "cap", "u", "s", "d",
                        List.of(
                                new EntryLine(customer, DEBIT, 100_00, "NGN", null),
                                new EntryLine(settlement, CREDIT, 100_00, "NGN", null)),
                        id);

        assertThatThrownBy(() -> posting.post(unrelated))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).errorCode())
                .isEqualTo(ErrorCode.HOLD_NOT_ACTIVE);
    }

    @Test
    @DisplayName("the consumed hold is part of the fingerprint")
    void capture_changes_the_fingerprint() {
        UUID id = holds.place(hold("h1", 500_00));
        var entries =
                List.of(
                        new EntryLine(customer, DEBIT, 500_00, "NGN", null),
                        new EntryLine(settlement, CREDIT, 500_00, "NGN", null));

        posting.post(new PostTransactionCommand(tenant, "k", "u", "s", "d", entries, id));

        assertThatThrownBy(
                        () -> posting.post(new PostTransactionCommand(tenant, "k", "u", "s", "d", entries, null)))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).errorCode())
                .as("capturing a hold is economics: the same key without it is a different request")
                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    @DisplayName("a crashed caller can read hold state without mutating it")
    void state_can_be_read_without_probing() {
        UUID id = holds.place(hold("h1", 300_00));

        HoldView view = holds.find(tenant, id);

        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.amountMinor()).isEqualTo(300_00);
        assertThat(holdsTotal()).as("asking must never be the thing that changes the answer").isEqualTo(300_00);
    }
}
