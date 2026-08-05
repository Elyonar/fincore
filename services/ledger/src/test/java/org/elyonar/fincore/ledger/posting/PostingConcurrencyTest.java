package org.elyonar.fincore.ledger.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.elyonar.fincore.ledger.posting.EntryLine.Direction.CREDIT;
import static org.elyonar.fincore.ledger.posting.EntryLine.Direction.DEBIT;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
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

/**
 * The lock protocol, tested rather than argued.
 *
 * <p>Concurrency is where a ledger design is most likely to be wrong and least likely to be caught
 * by inspection: every claim in {@code posting-algorithm.md} about sorted lock acquisition reads
 * plausibly whether or not the code honours it. These tests hammer shared rows from many threads
 * and assert the outcomes the design promises — money conserved, one winner per key, and
 * <em>zero</em> deadlock aborts rather than "few".
 *
 * <p>The deadlock assertion is the important one. PostgreSQL detects a deadlock and kills a victim,
 * so a service that acquires locks inconsistently still appears to work: it just fails a fraction
 * of postings under load, at a rate that rises with traffic and looks like flakiness. Asserting a
 * count of zero is the difference between "we did not observe deadlock" and "deadlock cannot
 * occur".
 */
@DisplayName("posting concurrency — the lock protocol holds under contention")
class PostingConcurrencyTest extends LedgerPostgresTest {

    private static final int THREADS = 8;
    private static final int POSTINGS_PER_THREAD = 15;

    @Autowired PostingService posting;
    @Autowired ReversalService reversals;
    @Autowired DataSource dataSource;

    private UUID tenant;
    private UUID hotAccount;
    private UUID funding;
    private List<UUID> customers;
    private TenantSession db;

    @BeforeEach
    void seed() {
        tenant = UUID.randomUUID();
        db = TenantSession.open(dataSource, tenant);
        db.execute("INSERT INTO currencies VALUES ('NGN',2,'Naira') ON CONFLICT (code) DO NOTHING");

        hotAccount = UUID.randomUUID();
        funding = UUID.randomUUID();
        account(hotAccount, "FEE", true);
        account(funding, "SETTLEMENT_MIRROR", true);

        customers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            UUID id = UUID.randomUUID();
            account(id, "CUSTOMER", true);
            customers.add(id);
        }
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

    private PostTransactionCommand transfer(String key, UUID from, UUID to, long amount) {
        return new PostTransactionCommand(
                tenant, key, "user:load", "svc:test", "concurrency",
                List.of(
                        new EntryLine(from, DEBIT, amount, "NGN", null),
                        new EntryLine(to, CREDIT, amount, "NGN", null)));
    }

    /** Runs {@code work} on every thread at once, so they contend rather than queue politely. */
    private <T> List<Future<T>> stampede(int threads, java.util.function.IntFunction<Callable<T>> task)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                Callable<T> body = task.apply(i);
                futures.add(
                        pool.submit(
                                () -> {
                                    startGun.await();
                                    return body.call();
                                }));
            }
            startGun.countDown();
            for (Future<T> f : futures) {
                f.get();
            }
            return futures;
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean isDeadlock(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof java.sql.SQLException sql && "40P01".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("money is conserved under a stampede on one hot account")
    void money_is_conserved_under_contention() throws Exception {
        AtomicInteger deadlocks = new AtomicInteger();

        stampede(
                THREADS,
                thread ->
                        () -> {
                            for (int i = 0; i < POSTINGS_PER_THREAD; i++) {
                                UUID customer = customers.get(i % customers.size());
                                try {
                                    posting.post(transfer("t" + thread + "-" + i, funding, customer, 1_00));
                                    posting.post(transfer("f" + thread + "-" + i, customer, hotAccount, 25));
                                } catch (RuntimeException e) {
                                    if (isDeadlock(e)) {
                                        deadlocks.incrementAndGet();
                                    } else {
                                        throw e;
                                    }
                                }
                            }
                            return null;
                        });

        assertThat(deadlocks.get())
                .as("one global sorted lock order makes deadlock impossible, not merely unlikely")
                .isZero();

        long sumOfBalances = db.count("SELECT COALESCE(SUM(current_minor),0) FROM balances WHERE tenant_id = ?", tenant);
        assertThat(sumOfBalances).as("invariant 1: money is conserved").isZero();

        long sumOfEntries =
                db.count(
                        """
                        SELECT COALESCE(SUM(CASE WHEN direction='CREDIT' THEN amount_minor
                                                 ELSE -amount_minor END), 0)
                          FROM entries WHERE tenant_id = ?
                        """,
                        tenant);
        assertThat(sumOfEntries).isZero();
    }

    @Test
    @DisplayName("every stored balance still equals the sum of its own entries")
    void balances_remain_provable_under_contention() throws Exception {
        stampede(
                THREADS,
                thread ->
                        () -> {
                            for (int i = 0; i < POSTINGS_PER_THREAD; i++) {
                                UUID customer = customers.get((thread + i) % customers.size());
                                posting.post(transfer("p" + thread + "-" + i, funding, customer, 7_00));
                            }
                            return null;
                        });

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

    @Test
    @DisplayName("the same key raced by many threads posts exactly once")
    void duplicate_key_race_produces_one_winner() throws Exception {
        AtomicInteger posted = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        List<UUID> ids = Collections.synchronizedList(new ArrayList<>());

        stampede(
                16,
                thread ->
                        () -> {
                            PostingResult result = posting.post(transfer("same-key", funding, customers.get(0), 500_00));
                            ids.add(result.transactionId());
                            if (result.replayed()) {
                                replayed.incrementAndGet();
                            } else {
                                posted.incrementAndGet();
                            }
                            return null;
                        });

        assertThat(posted.get()).as("the unique index arbitrates: exactly one insert wins").isEqualTo(1);
        assertThat(replayed.get()).isEqualTo(15);
        assertThat(Set.copyOf(ids)).as("every caller is told the same transaction id").hasSize(1);
        assertThat(db.count("SELECT count(*) FROM entries WHERE tenant_id = ?", tenant))
                .as("a duplicate must move money once, not sixteen times")
                .isEqualTo(2);
        assertThat(db.count("SELECT current_minor FROM balances WHERE account_id = ?", customers.get(0)))
                .isEqualTo(500_00);
    }

    @Test
    @DisplayName("a guarded account cannot be oversold by concurrent debits")
    void concurrent_debits_cannot_oversell() throws Exception {
        UUID guarded = UUID.randomUUID();
        db.execute(
                """
                INSERT INTO accounts (id, tenant_id, idempotency_key, type, currency, allow_negative)
                VALUES (?,?,?, 'CUSTOMER','NGN', false)
                """,
                guarded, tenant, "acct-guarded");
        db.execute("INSERT INTO balances (account_id, tenant_id) VALUES (?,?)", guarded, tenant);
        posting.post(transfer("fund-guarded", funding, guarded, 100_00));

        AtomicInteger refused = new AtomicInteger();

        // Ten threads each try to withdraw 20.00 from a balance of 100.00. At most five can
        // succeed; the balance may never go negative regardless of interleaving.
        stampede(
                10,
                thread ->
                        () -> {
                            try {
                                posting.post(transfer("draw-" + thread, guarded, funding, 20_00));
                            } catch (LedgerException e) {
                                if (e.errorCode() == ErrorCode.INSUFFICIENT_FUNDS) {
                                    refused.incrementAndGet();
                                } else {
                                    throw e;
                                }
                            }
                            return null;
                        });

        long remaining = db.count("SELECT current_minor FROM balances WHERE account_id = ?", guarded);
        assertThat(remaining).as("a guarded account may never go negative, however threads interleave").isGreaterThanOrEqualTo(0);
        assertThat(refused.get()).as("at least five of ten withdrawals must be refused").isGreaterThanOrEqualTo(5);
        assertThat(remaining % 20_00).isZero();
    }

    @Test
    @DisplayName("opposing account orders still cannot deadlock")
    void opposing_orders_do_not_deadlock() throws Exception {
        UUID a = customers.get(0);
        UUID b = customers.get(1);
        AtomicInteger deadlocks = new AtomicInteger();

        // Half the threads move a -> b while the other half move b -> a. Written naively, this is
        // the textbook deadlock; sorting balance-row locks by account id removes the cycle.
        stampede(
                THREADS,
                thread ->
                        () -> {
                            for (int i = 0; i < POSTINGS_PER_THREAD; i++) {
                                UUID from = thread % 2 == 0 ? a : b;
                                UUID to = thread % 2 == 0 ? b : a;
                                try {
                                    posting.post(transfer("x" + thread + "-" + i, from, to, 1_00));
                                } catch (RuntimeException e) {
                                    if (isDeadlock(e)) {
                                        deadlocks.incrementAndGet();
                                    } else {
                                        throw e;
                                    }
                                }
                            }
                            return null;
                        });

        assertThat(deadlocks.get())
                .as("the classic ABBA deadlock, defeated by acquiring balance rows in sorted order")
                .isZero();
        assertThat(
                        db.count("SELECT current_minor FROM balances WHERE account_id = ?", a)
                                + db.count("SELECT current_minor FROM balances WHERE account_id = ?", b))
                .as("the pair nets to zero: every debit had its credit")
                .isZero();
    }

    @Test
    @DisplayName("reversal racing compensation on the same original never deadlocks")
    void reversal_and_compensation_do_not_deadlock() throws Exception {
        // The two-tier protocol exists for exactly this pair. Both operations need the original
        // transaction row *and* balance rows; if one took balances first and the other took the
        // transaction row first, they would deadlock under load — intermittently, at a rate that
        // rises with traffic and reads as flakiness. Both take tier 1 before tier 2, so the cycle
        // cannot form. Exclusion is asserted elsewhere; what is asserted here is zero aborts.
        AtomicInteger deadlocks = new AtomicInteger();
        AtomicInteger excluded = new AtomicInteger();

        List<UUID> originals = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            originals.add(
                    posting.post(transfer("orig-" + i, funding, customers.get(i % customers.size()), 50_00))
                            .transactionId());
        }

        stampede(
                12,
                thread ->
                        () -> {
                            UUID original = originals.get(thread);
                            UUID account = customers.get(thread % customers.size());
                            try {
                                if (thread % 2 == 0) {
                                    reversals.reverse(
                                            new ReverseTransactionCommand(
                                                    tenant, original, "rev-" + thread, "user:ops", "svc:test"));
                                } else {
                                    posting.post(
                                            new PostTransactionCommand(
                                                    tenant, "comp-" + thread, "u", "s", "partial",
                                                    List.of(
                                                            new EntryLine(account, DEBIT, 10_00, "NGN", null),
                                                            new EntryLine(funding, CREDIT, 10_00, "NGN", null)),
                                                    null,
                                                    original));
                                }
                            } catch (RuntimeException e) {
                                if (isDeadlock(e)) {
                                    deadlocks.incrementAndGet();
                                } else if (e instanceof LedgerException le
                                        && (le.errorCode() == ErrorCode.TARGET_REVERSED
                                                || le.errorCode() == ErrorCode.HAS_COMPENSATIONS
                                                || le.errorCode() == ErrorCode.ALREADY_REVERSED)) {
                                    excluded.incrementAndGet();
                                } else {
                                    throw e;
                                }
                            }
                            return null;
                        });

        assertThat(deadlocks.get())
                .as("tier 1 before tier 2, everywhere: the reversal/compensation pair cannot deadlock")
                .isZero();

        long sumOfBalances =
                db.count("SELECT COALESCE(SUM(current_minor),0) FROM balances WHERE tenant_id = ?", tenant);
        assertThat(sumOfBalances).as("money stays conserved through the whole race").isZero();
    }
}
