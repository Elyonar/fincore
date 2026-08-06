package org.elyonar.fincore.ledger.posting;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import org.elyonar.fincore.ledger.shared.LedgerException;
import org.elyonar.fincore.ledger.support.SpringHarness;
import org.elyonar.fincore.ledger.support.TenantSession;

/**
 * Properties that must hold for <em>any</em> sequence of operations, not just the ones I thought to
 * write down.
 *
 * <p>Every example-based test in this suite encodes what its author already believed. That is the
 * blind spot a ledger cannot afford: the bug that matters is the interleaving nobody imagined. jqwik
 * generates operation sequences and shrinks a failure to its smallest reproducing case, which is
 * what turns "something went wrong under load" into a one-line counterexample.
 *
 * <p>The properties asserted are the ledger's own invariants — conservation, and balances provable
 * from entries — held after arbitrary sequences of postings, reversals and rejected operations.
 * Rejections are deliberately generated too: an operation that fails must leave the ledger exactly
 * as it found it, and that is where partial writes would show.
 */
class LedgerPropertiesTest {

    private PostingService posting;
    private ReversalService reversals;
    private DataSource dataSource;

    private UUID tenant;
    private List<UUID> accounts;
    private TenantSession db;

    @BeforeTry
    void freshLedger() {
        var context = SpringHarness.context();
        posting = context.getBean(PostingService.class);
        reversals = context.getBean(ReversalService.class);
        dataSource = context.getBean(DataSource.class);

        tenant = UUID.randomUUID();
        db = TenantSession.open(dataSource, tenant);
        db.execute("INSERT INTO currencies VALUES ('NGN',2,'Naira') ON CONFLICT (code) DO NOTHING");

        accounts = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            UUID id = UUID.randomUUID();
            db.execute(
                    "INSERT INTO accounts (id, tenant_id, idempotency_key, type, currency, allow_negative)"
                            + " VALUES (?,?,?, 'CUSTOMER','NGN', true)",
                    id, tenant, "acct-" + id);
            db.execute("INSERT INTO balances (account_id, tenant_id) VALUES (?,?)", id, tenant);
            accounts.add(id);
        }
    }

    /**
     * Closes the pinned session after every try.
     *
     * <p>Not housekeeping: a property runs its body dozens of times, so a connection held per try
     * exhausts the pool within one property. The first run of this suite did exactly that — which
     * is a small demonstration of the point, since no example-based test had ever repeated an
     * operation often enough to notice.
     */
    @AfterTry
    void closeSession() {
        if (db != null) {
            db.close();
        }
    }

    /** A move between two accounts, or a reversal of an earlier one. */
    record Operation(int from, int to, long amountMinor, boolean reverseInstead) {}

    @Provide
    Arbitrary<List<Operation>> operationSequences() {
        Arbitrary<Operation> op =
                Arbitraries.integers().between(0, 3)
                        .flatMap(from -> Arbitraries.integers().between(0, 3)
                                .filter(to -> !to.equals(from))
                                .flatMap(to -> Arbitraries.longs().between(1, 5_000_00)
                                        .flatMap(amount -> Arbitraries.of(true, false)
                                                .map(rev -> new Operation(from, to, amount, rev)))));
        return op.list().ofMinSize(1).ofMaxSize(12);
    }

    @Property(tries = 25)
    void money_is_conserved_under_any_sequence(@ForAll("operationSequences") List<Operation> operations) {
        List<UUID> posted = apply(operations);

        long sumOfBalances =
                db.count("SELECT COALESCE(SUM(current_minor),0) FROM balances WHERE tenant_id = ?", tenant);
        assertThat(sumOfBalances)
                .as("invariant 1 after %d operations (%d committed)", operations.size(), posted.size())
                .isZero();
    }

    @Property(tries = 25)
    void every_balance_stays_provable(@ForAll("operationSequences") List<Operation> operations) {
        apply(operations);

        long mismatched =
                db.count(
                        """
                        SELECT count(*) FROM balances b
                         WHERE b.tenant_id = ?
                           AND b.current_minor <> COALESCE((
                                 SELECT SUM(CASE WHEN e.direction='CREDIT' THEN e.amount_minor
                                                 ELSE -e.amount_minor END)
                                   FROM entries e
                                  WHERE e.tenant_id = b.tenant_id AND e.account_id = b.account_id), 0)
                        """,
                        tenant);
        assertThat(mismatched).as("invariant 2: no cached balance drifted from its entries").isZero();
    }

    @Property(tries = 25)
    void rejected_operations_leave_nothing_behind(@ForAll("operationSequences") List<Operation> operations) {
        apply(operations);

        // Every transaction row must have entries. A rejection that got halfway would leave a
        // registered key with no entries behind it — the shape of a partial write.
        long orphaned =
                db.count(
                        """
                        SELECT count(*) FROM ledger_transactions t
                         WHERE t.tenant_id = ?
                           AND NOT EXISTS (SELECT 1 FROM entries e
                                            WHERE e.tenant_id = t.tenant_id AND e.transaction_id = t.id)
                        """,
                        tenant);
        assertThat(orphaned).as("a rejection is total: no transaction exists without its entries").isZero();
    }

    /** Applies the sequence, tolerating domain rejections — they are part of what is being tested. */
    private List<UUID> apply(List<Operation> operations) {
        List<UUID> posted = new ArrayList<>();
        int i = 0;
        for (Operation op : operations) {
            String key = "prop-" + (i++);
            try {
                if (op.reverseInstead() && !posted.isEmpty()) {
                    reversals.reverse(
                            new ReverseTransactionCommand(
                                    tenant, posted.get(posted.size() - 1), "rev-" + key, "u", "s"));
                } else {
                    posted.add(
                            posting.post(
                                            new PostTransactionCommand(
                                                    tenant, key, "u", "s", "property",
                                                    List.of(
                                                            new EntryLine(
                                                                    accounts.get(op.from()),
                                                                    EntryLine.Direction.DEBIT,
                                                                    op.amountMinor(), "NGN", null),
                                                            new EntryLine(
                                                                    accounts.get(op.to()),
                                                                    EntryLine.Direction.CREDIT,
                                                                    op.amountMinor(), "NGN", null))))
                                    .transactionId());
                }
            } catch (LedgerException expected) {
                // Domain rejections are a valid outcome of a generated sequence — already
                // reversed, compensated, and so on. The properties above assert the ledger is
                // unharmed by them, which is the point.
            }
        }
        return posted;
    }
}
