package org.elyonar.fincore.ledger.failure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.elyonar.fincore.ledger.posting.EntryLine.Direction.CREDIT;
import static org.elyonar.fincore.ledger.posting.EntryLine.Direction.DEBIT;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.elyonar.fincore.events.EventPublisher;
import org.elyonar.fincore.ledger.outbox.OutboxRelay;
import org.elyonar.fincore.events.DomainEvent;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * What the ledger does when things break underneath it.
 *
 * <p>Every other suite tests the ledger working. These test it failing, because the guarantees that
 * matter most — a rejection leaves nothing behind, an unacknowledged event is retried, a duplicate
 * delivery is a consumer's problem and not a second payment — are only observable when something
 * goes wrong. A system that has only ever been tested succeeding has had its most important claims
 * proven the least.
 */
@Import(FailureInjectionTest.FlakyPublisherConfig.class)
@DisplayName("failure injection — what happens when things break underneath")
class FailureInjectionTest extends LedgerPostgresTest {

    /** A publisher that can be told to fail, so relay behaviour is observable rather than inferred. */
    @TestConfiguration
    static class FlakyPublisherConfig {

        @Bean
        @Primary
        FlakyPublisher flakyPublisher() {
            return new FlakyPublisher();
        }
    }

    static class FlakyPublisher implements EventPublisher {

        @Override
        public String name() {
            return "flaky";
        }

        @Override
        public boolean delivers() {
            return true;
        }

        volatile boolean brokerDown = false;
        volatile int publishAttempts = 0;

        @Override
        public List<Long> publish(List<DomainEvent> batch) {
            publishAttempts++;
            if (brokerDown) {
                // Acknowledges nothing: the broker took the batch and never confirmed, or died
                // between receiving and confirming. Indistinguishable from the outside.
                return List.of();
            }
            return batch.stream().map(DomainEvent::id).toList();
        }
    }

    @Autowired PostingService posting;
    @Autowired OutboxRelay relay;
    @Autowired FlakyPublisher publisher;
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
        publisher.brokerDown = false;
        while (relay.relayBatch(500) > 0) {
            // start from a drained queue
        }
    }

    @AfterEach
    void tearDown() {
        publisher.brokerDown = false;
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

    @Test
    @DisplayName("a broker that never acknowledges leaves events pending, not lost")
    void unacknowledged_events_stay_pending() {
        post("tx-1", 500_00);
        publisher.brokerDown = true;

        assertThat(relay.relayBatch(10)).as("nothing may be marked published without an ack").isZero();
        assertThat(db.count("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND published_at IS NULL", tenant))
                .isEqualTo(1);

        publisher.brokerDown = false;
        assertThat(relay.relayBatch(10)).as("and it is delivered once the broker returns").isEqualTo(1);
    }

    @Test
    @DisplayName("a relay crash between publish and mark leaves the event to be redelivered")
    void crash_between_publish_and_mark_redelivers() {
        post("tx-1", 500_00);

        // The broker accepted it, then the relay died before recording that. On restart the row is
        // still pending, so the event goes out twice — at-least-once, exactly as the contract says.
        publisher.brokerDown = true;
        relay.relayBatch(10);
        publisher.brokerDown = false;

        int attemptsBefore = publisher.publishAttempts;
        assertThat(relay.relayBatch(10)).isEqualTo(1);
        assertThat(publisher.publishAttempts)
                .as("the event is published again; consumers deduplicate on outbox id")
                .isGreaterThan(attemptsBefore);
    }

    @Test
    @DisplayName("a duplicate delivery moves money once, not twice")
    void duplicate_delivery_is_not_a_second_payment() {
        post("tx-dup", 500_00);
        post("tx-dup", 500_00); // the caller retried an unknown outcome, as the contract requires

        assertThat(db.count("SELECT current_minor FROM balances WHERE account_id = ?", b)).isEqualTo(500_00);
        assertThat(db.count("SELECT count(*) FROM entries WHERE tenant_id = ?", tenant)).isEqualTo(2);
    }

    @Test
    @DisplayName("a connection lost mid-transaction leaves no partial posting")
    void connection_lost_mid_transaction_writes_nothing() throws Exception {
        long before = db.count("SELECT count(*) FROM entries WHERE tenant_id = ?", tenant);

        // Kill the backend serving an open transaction that has already written an entry. This is
        // the ungraceful case: not a rollback the application chose, but the database vanishing
        // underneath it.
        try (var doomed = dataSource.getConnection()) {
            doomed.setAutoCommit(false);
            try (var ctx = doomed.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
                ctx.setString(1, tenant.toString());
                ctx.execute();
            }
            int pid;
            try (var rs = doomed.createStatement().executeQuery("SELECT pg_backend_pid()")) {
                rs.next();
                pid = rs.getInt(1);
            }
            try (var tx = doomed.prepareStatement(
                    "INSERT INTO ledger_transactions (id, tenant_id, idempotency_key, request_fingerprint,"
                            + " initiated_by, executed_by) VALUES (?,?,?, 'fp','u','s')")) {
                tx.setObject(1, UUID.randomUUID());
                tx.setObject(2, tenant);
                tx.setString(3, "doomed-" + UUID.randomUUID());
                tx.executeUpdate();
            }

            try (var killer = dataSource.getConnection();
                    var kill = killer.prepareStatement("SELECT pg_terminate_backend(?)")) {
                kill.setInt(1, pid);
                kill.execute();
            }

            assertThatThrownBy(doomed::commit)
                    .as("the commit cannot succeed once the backend is gone")
                    .isInstanceOf(Exception.class);
        } catch (Exception terminationNoise) {
            // Closing a terminated connection throws; the assertion above is the contract.
        }

        assertThat(db.count("SELECT count(*) FROM entries WHERE tenant_id = ?", tenant))
                .as("an uncommitted transaction leaves nothing, however violently it ended")
                .isEqualTo(before);
        assertThat(db.count("SELECT count(*) FROM ledger_transactions WHERE tenant_id = ?"
                        + " AND idempotency_key LIKE 'doomed-%'", tenant))
                .as("and the idempotency key stays free for a genuine retry")
                .isZero();
    }

    // Deliberately absent: a test that culls every idle connection and asserts the next posting
    // succeeds. It was written, and removed for two reasons. It tests HikariCP's validation
    // behaviour rather than any guarantee the ledger makes — a connection returned to the pool
    // moments before being killed skips validation, and the documented answer to that is the retry
    // rule, not transparent recovery. And its blast radius is the whole shared pool, so it broke
    // unrelated suites: a failure-injection test that injects failure into other tests is measuring
    // itself. The guarantee that matters — a connection dying mid-transaction leaves nothing behind
    // and frees the key — is covered above, surgically, by terminating one known backend.
    //
    // Pool hardening (keepalive, max-lifetime) is configured in application.yml.
}
