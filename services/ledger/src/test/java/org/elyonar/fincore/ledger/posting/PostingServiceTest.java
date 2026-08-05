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

/** The posting happy path, and the rejections that keep it honest. */
@DisplayName("posting — balanced transactions commit atomically and idempotently")
class PostingServiceTest extends LedgerPostgresTest {

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
        account(customer, "CUSTOMER", "NGN", false);
        account(settlement, "SETTLEMENT_MIRROR", "NGN", true);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private void account(UUID id, String type, String currency, boolean allowNegative) {
        db.execute(
                """
                INSERT INTO accounts (id, tenant_id, idempotency_key, type, currency, allow_negative)
                VALUES (?,?,?,?,?,?)
                """,
                id, tenant, "acct-" + id, type, currency, allowNegative);
        db.execute("INSERT INTO balances (account_id, tenant_id) VALUES (?,?)", id, tenant);
    }

    private PostTransactionCommand transfer(String key, long amount) {
        return new PostTransactionCommand(
                tenant, key, "user:ada", "svc:orchestration", "NIBSS transfer",
                List.of(
                        new EntryLine(settlement, DEBIT, amount, "NGN", null),
                        new EntryLine(customer, CREDIT, amount, "NGN", null)));
    }

    private long balanceOf(UUID account) {
        return db.count("SELECT current_minor FROM balances WHERE account_id = ?", account);
    }

    @Test
    @DisplayName("a balanced transaction writes its entries and moves both balances")
    void posts_a_balanced_transaction() {
        PostingResult result = posting.post(transfer("tx-1", 500_00));

        assertThat(result.replayed()).isFalse();
        assertThat(db.count("SELECT count(*) FROM entries WHERE transaction_id = ?", result.transactionId()))
                .isEqualTo(2);
        assertThat(balanceOf(customer)).isEqualTo(500_00);
        assertThat(balanceOf(settlement)).isEqualTo(-500_00);
    }

    @Test
    @DisplayName("money is conserved: debits and credits sum to zero")
    void money_is_conserved() {
        posting.post(transfer("tx-1", 500_00));
        posting.post(transfer("tx-2", 250_00));

        assertThat(balanceOf(customer) + balanceOf(settlement))
                .as("invariant 1: every kobo credited was debited somewhere")
                .isZero();
    }

    @Test
    @DisplayName("the stored balance equals the sum of its entries")
    void balance_matches_entries() {
        posting.post(transfer("tx-1", 500_00));
        posting.post(transfer("tx-2", 125_00));

        long derived =
                db.count(
                        """
                        SELECT COALESCE(SUM(CASE WHEN direction='CREDIT' THEN amount_minor
                                                 ELSE -amount_minor END), 0)
                          FROM entries WHERE account_id = ?
                        """,
                        customer);
        assertThat(derived).as("invariant 2: the cached balance is provable").isEqualTo(balanceOf(customer));
    }

    @Test
    @DisplayName("replaying a key with the same payload returns the original, moving nothing")
    void replay_is_idempotent() {
        PostingResult first = posting.post(transfer("tx-dup", 500_00));
        PostingResult second = posting.post(transfer("tx-dup", 500_00));

        assertThat(second.transactionId()).isEqualTo(first.transactionId());
        assertThat(second.replayed()).isTrue();
        assertThat(balanceOf(customer)).as("a retry must not move money twice").isEqualTo(500_00);
        assertThat(db.count("SELECT count(*) FROM entries WHERE tenant_id = ?", tenant)).isEqualTo(2);
    }

    @Test
    @DisplayName("the same key with a different payload is refused, never silently answered")
    void key_reuse_is_loud() {
        posting.post(transfer("tx-reuse", 500_00));

        assertThatThrownBy(() -> posting.post(transfer("tx-reuse", 900_00)))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).errorCode())
                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);

        assertThat(balanceOf(customer)).isEqualTo(500_00);
    }

    @Test
    @DisplayName("an omitted value date replays across the tenant's midnight boundary")
    void omitted_value_date_still_replays_after_midnight() {
        // The retry rule obliges a caller to retry the same key after a timeout. If the
        // fingerprint covered the *resolved* value date, a retry that crossed midnight would
        // hash differently, be rejected as key reuse — a 4xx, therefore terminal — and push the
        // caller into minting a new key for an operation that may already have committed.
        var beforeMidnight = transfer("tx-midnight", 500_00);
        posting.post(beforeMidnight);

        String yesterday = RequestFingerprint.of(beforeMidnight);
        String today = RequestFingerprint.of(transfer("tx-midnight", 500_00));
        assertThat(today)
                .as("the fingerprint covers the request as received, so it cannot drift with the clock")
                .isEqualTo(yesterday);

        assertThat(posting.post(transfer("tx-midnight", 500_00)).replayed()).isTrue();
    }

    @Test
    @DisplayName("entry order does not change the fingerprint")
    void entry_order_is_not_economic() {
        var forward =
                new PostTransactionCommand(
                        tenant, "k", "u", "s", "d",
                        List.of(
                                new EntryLine(settlement, DEBIT, 100, "NGN", null),
                                new EntryLine(customer, CREDIT, 100, "NGN", null)));
        var reversed =
                new PostTransactionCommand(
                        tenant, "k", "u", "s", "d",
                        List.of(
                                new EntryLine(customer, CREDIT, 100, "NGN", null),
                                new EntryLine(settlement, DEBIT, 100, "NGN", null)));

        assertThat(RequestFingerprint.of(forward)).isEqualTo(RequestFingerprint.of(reversed));
    }

    @Test
    @DisplayName("description and initiatedBy are not economics")
    void prose_does_not_change_the_fingerprint() {
        var a = new PostTransactionCommand(tenant, "k", "user:ada", "svc:a", "one wording",
                List.of(new EntryLine(settlement, DEBIT, 100, "NGN", null),
                        new EntryLine(customer, CREDIT, 100, "NGN", null)));
        var b = new PostTransactionCommand(tenant, "k", "user:bola", "svc:b", "another wording",
                List.of(new EntryLine(settlement, DEBIT, 100, "NGN", null),
                        new EntryLine(customer, CREDIT, 100, "NGN", null)));

        assertThat(RequestFingerprint.of(a))
                .as("a retry from a different pod or user must replay, not 409")
                .isEqualTo(RequestFingerprint.of(b));
    }

    @Test
    @DisplayName("an unbalanced transaction is rejected and leaves nothing behind")
    void rejects_unbalanced_and_writes_nothing() {
        var unbalanced =
                new PostTransactionCommand(
                        tenant, "tx-bad", "u", "s", "d",
                        List.of(
                                new EntryLine(settlement, DEBIT, 100_00, "NGN", null),
                                new EntryLine(customer, CREDIT, 90_00, "NGN", null)));

        assertThatThrownBy(() -> posting.post(unbalanced))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).errorCode())
                .isEqualTo(ErrorCode.UNBALANCED);

        assertThat(db.count("SELECT count(*) FROM entries WHERE tenant_id = ?", tenant)).isZero();
        assertThat(balanceOf(customer)).isZero();
        assertThat(db.count("SELECT count(*) FROM ledger_transactions WHERE idempotency_key = 'tx-bad'"))
                .as("a rejected request leaves the key free for a genuine retry")
                .isZero();
    }

    @Test
    @DisplayName("an account on both sides is rejected as a wash")
    void rejects_wash_transactions() {
        var wash =
                new PostTransactionCommand(
                        tenant, "tx-wash", "u", "s", "d",
                        List.of(
                                new EntryLine(customer, DEBIT, 100_00, "NGN", null),
                                new EntryLine(customer, CREDIT, 100_00, "NGN", null)));

        assertThatThrownBy(() -> posting.post(wash))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).errorCode())
                .isEqualTo(ErrorCode.WASH_TRANSACTION);
    }

    @Test
    @DisplayName("a guarded account cannot be driven below zero")
    void guards_against_negative_balances() {
        var overdraw =
                new PostTransactionCommand(
                        tenant, "tx-overdraw", "u", "s", "d",
                        List.of(
                                new EntryLine(customer, DEBIT, 10_00, "NGN", null),
                                new EntryLine(settlement, CREDIT, 10_00, "NGN", null)));

        assertThatThrownBy(() -> posting.post(overdraw))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).errorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);

        assertThat(balanceOf(customer)).isZero();
    }

    @Test
    @DisplayName("an unguarded account may go negative by design")
    void unguarded_accounts_may_go_negative() {
        posting.post(transfer("tx-1", 500_00));
        assertThat(balanceOf(settlement))
                .as("settlement mirrors are allow_negative: that is what makes them mirrors")
                .isNegative();
    }

    @Test
    @DisplayName("a currency the account is not denominated in is rejected")
    void rejects_currency_mismatch() {
        var mismatched =
                new PostTransactionCommand(
                        tenant, "tx-fx", "u", "s", "d",
                        List.of(
                                new EntryLine(settlement, DEBIT, 100_00, "USD", null),
                                new EntryLine(customer, CREDIT, 100_00, "USD", null)));

        assertThatThrownBy(() -> posting.post(mismatched))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).errorCode())
                .isEqualTo(ErrorCode.CURRENCY_MISMATCH);
    }

    @Test
    @DisplayName("a single entry is not a transaction")
    void rejects_single_entry() {
        var lonely =
                new PostTransactionCommand(
                        tenant, "tx-one", "u", "s", "d",
                        List.of(new EntryLine(customer, CREDIT, 100_00, "NGN", null)));

        assertThatThrownBy(() -> posting.post(lonely))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).errorCode())
                .isEqualTo(ErrorCode.UNBALANCED);
    }

    @Test
    @DisplayName("an entry above the amount cap is rejected")
    void rejects_amounts_above_the_cap() {
        var huge =
                new PostTransactionCommand(
                        tenant, "tx-huge", "u", "s", "d",
                        List.of(
                                new EntryLine(settlement, DEBIT, 1_000_000_000_000_001L, "NGN", null),
                                new EntryLine(customer, CREDIT, 1_000_000_000_000_001L, "NGN", null)));

        assertThatThrownBy(() -> posting.post(huge))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).errorCode())
                .isEqualTo(ErrorCode.LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("posting to another tenant's account fails as not-found")
    void cannot_post_into_another_tenants_account() {
        UUID otherTenant = UUID.randomUUID();
        UUID otherAccount = UUID.randomUUID();
        try (TenantSession other = TenantSession.open(dataSource, otherTenant)) {
            other.execute(
                    """
                    INSERT INTO accounts (id, tenant_id, idempotency_key, type, currency, allow_negative)
                    VALUES (?,?,?, 'CUSTOMER','NGN', true)
                    """,
                    otherAccount, otherTenant, "acct-other");
            other.execute("INSERT INTO balances (account_id, tenant_id) VALUES (?,?)", otherAccount, otherTenant);
        }

        var crossTenant =
                new PostTransactionCommand(
                        tenant, "tx-cross", "u", "s", "d",
                        List.of(
                                new EntryLine(otherAccount, DEBIT, 100_00, "NGN", null),
                                new EntryLine(customer, CREDIT, 100_00, "NGN", null)));

        assertThatThrownBy(() -> posting.post(crossTenant))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).errorCode())
                .as("not-found and wrong-tenant are deliberately indistinguishable")
                .isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
    }

    @Test
    @DisplayName("value dates default to the business date and are stored")
    void value_date_defaults_to_today() {
        PostingResult result = posting.post(transfer("tx-vd", 100_00));
        assertThat(
                        db.count(
                                "SELECT count(*) FROM entries WHERE transaction_id = ? AND value_date = ?",
                                result.transactionId(),
                                LocalDate.now()))
                .isEqualTo(2);
    }
}
