package org.elyonar.fincore.ledger.invariant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.elyonar.fincore.ledger.posting.EntryLine.Direction.CREDIT;
import static org.elyonar.fincore.ledger.posting.EntryLine.Direction.DEBIT;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.elyonar.fincore.ledger.posting.EntryLine;
import org.elyonar.fincore.ledger.posting.PostTransactionCommand;
import org.elyonar.fincore.ledger.posting.PostingService;
import org.elyonar.fincore.ledger.support.LedgerPostgresTest;
import org.elyonar.fincore.ledger.support.TenantSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The spike the design asked for, kept as a permanent test.
 *
 * <p>Anchors and the outbox relay both depend on knowing which entries are certainly settled. A
 * horizon that advanced too far would make every dependent check pass while verifying nothing, so
 * this proves the primitive directly rather than through its consumers.
 */
@DisplayName("quiesce horizon — never advances past an in-flight writer")
class QuiesceHorizonTest extends LedgerPostgresTest {

    @Autowired QuiesceHorizon horizon;
    @Autowired PostingService posting;
    @Autowired DataSource dataSource;

    private UUID tenant;
    private UUID a;
    private UUID b;
    private TenantSession db;

    @BeforeEach
    void seed() {
        tenant = UUID.randomUUID();
        a = UUID.randomUUID();
        b = UUID.randomUUID();
        db = TenantSession.open(dataSource, tenant);
        db.execute("INSERT INTO currencies VALUES ('NGN',2,'Naira') ON CONFLICT (code) DO NOTHING");
        for (UUID id : List.of(a, b)) {
            db.execute(
                    "INSERT INTO accounts (id, tenant_id, idempotency_key, type, currency, allow_negative)"
                            + " VALUES (?,?,?, 'CUSTOMER','NGN', true)",
                    id, tenant, "acct-" + id);
            db.execute("INSERT INTO balances (account_id, tenant_id) VALUES (?,?)", id, tenant);
        }
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private void post(String key, long amount) {
        posting.post(
                new PostTransactionCommand(
                        tenant, key, "u", "s", "d",
                        List.of(
                                new EntryLine(a, DEBIT, amount, "NGN", null),
                                new EntryLine(b, CREDIT, amount, "NGN", null))));
    }

    /**
     * Waits for the horizon to reach {@code target}, and reports what it saw if it never does.
     *
     * <p>Transaction ids are cluster-wide, so an unrelated transaction open anywhere in this
     * PostgreSQL instance — another test context's scheduler, a service running against a different
     * database in the same container — holds the horizon below entries this test has already
     * committed. That is the guarantee working, not failing: it lags live traffic and never
     * overtakes it. Asserting on the horizon without first waiting for it tests the machine's
     * idleness rather than the ledger's correctness, and fails intermittently in exactly the way
     * that teaches people to re-run a suite instead of reading it.
     *
     * <p>The waiting belongs only where the test needs the horizon to have <em>advanced</em>. The
     * assertions about it <em>not</em> advancing past an in-flight writer are left to read the
     * horizon directly — waiting there would weaken the very property under test.
     */
    private void awaitBoundAtLeast(long target) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (horizon.settledEntryIdBound(tenant).orElse(-1L) < target) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(
                        "the horizon never reached entry id " + target + " within 20s (stalled at "
                                + horizon.settledEntryIdBound(tenant).orElse(-1L)
                                + "); something is holding a transaction open in this cluster");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted awaiting the quiesce horizon", e);
            }
        }
    }

    @Test
    @DisplayName("committed entries fall below the horizon")
    void committed_entries_are_settled() {
        post("tx-1", 100_00);
        long committedMax = db.count("SELECT MAX(id) FROM entries WHERE tenant_id = ?", tenant);

        awaitBoundAtLeast(committedMax);

        assertThat(horizon.settledEntryIdBound(tenant))
                .isPresent()
                .get()
                .satisfies(bound -> assertThat(bound).isGreaterThanOrEqualTo(committedMax));
    }

    @Test
    @DisplayName("the horizon never advances past an entry whose writer is still in flight")
    void horizon_excludes_in_flight_writers() throws Exception {
        post("tx-before", 100_00);
        // Settle this one first, so the slow writer below is unambiguously the oldest transaction
        // pinning the horizon. Without it, an unrelated older transaction elsewhere in the cluster
        // could commit mid-test and let the bound move for a reason that has nothing to do with the
        // writer this test is about — the assertion would then be measuring the wrong thing.
        awaitBoundAtLeast(db.count("SELECT MAX(id) FROM entries WHERE tenant_id = ?", tenant));

        try (Connection slow = dataSource.getConnection()) {
            slow.setAutoCommit(false);
            try (var ctx = slow.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
                ctx.setString(1, tenant.toString());
                ctx.execute();
            }

            // A transaction that has inserted an entry but not committed. Its id is allocated and
            // higher than everything before it, but the row is not settled.
            try (var tx =
                    slow.prepareStatement(
                            "INSERT INTO ledger_transactions (id, tenant_id, idempotency_key,"
                                    + " request_fingerprint, initiated_by, executed_by)"
                                    + " VALUES (?,?,?, 'fp','u','s')")) {
                tx.setObject(1, UUID.randomUUID());
                tx.setObject(2, tenant);
                tx.setString(3, "slow-" + UUID.randomUUID());
                tx.executeUpdate();
            }

            long boundWhileInFlight = horizon.settledEntryIdBound(tenant).orElse(0L);

            // Other traffic commits while the slow writer is still open.
            post("tx-during", 50_00);
            long afterCommit = horizon.settledEntryIdBound(tenant).orElse(0L);

            assertThat(afterCommit)
                    .as("the horizon cannot advance past the oldest in-flight transaction, "
                            + "however much later traffic commits")
                    .isEqualTo(boundWhileInFlight);

            slow.commit();
        }

        // With the slow writer finished, the horizon is free to move again.
        post("tx-after", 25_00);
        long allEntries = db.count("SELECT MAX(id) FROM entries WHERE tenant_id = ?", tenant);
        awaitBoundAtLeast(allEntries - 2);
        long finalBound = horizon.settledEntryIdBound(tenant).orElse(0L);
        assertThat(finalBound)
                .as("it lags live traffic and never overtakes it — the only safe direction")
                .isGreaterThanOrEqualTo(allEntries - 2);
    }

    @Test
    @DisplayName("an empty ledger yields no bound rather than a misleading zero")
    void empty_ledger_has_no_bound() {
        assertThat(horizon.oldestInFlightTransactionId()).isPositive();
    }
}
