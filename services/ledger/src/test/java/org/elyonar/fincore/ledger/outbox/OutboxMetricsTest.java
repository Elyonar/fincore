package org.elyonar.fincore.ledger.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.elyonar.fincore.ledger.posting.EntryLine.Direction.CREDIT;
import static org.elyonar.fincore.ledger.posting.EntryLine.Direction.DEBIT;

import io.micrometer.core.instrument.MeterRegistry;
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

@DisplayName("outbox metrics — the staleness alarm must measure, not decorate")
class OutboxMetricsTest extends LedgerPostgresTest {

    @Autowired PostingService posting;
    @Autowired OutboxRelay relay;
    @Autowired MeterRegistry registry;
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
        account(customer, false);
        account(settlement, true);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private void account(UUID id, boolean allowNegative) {
        db.execute(
                """
                INSERT INTO accounts (id, tenant_id, idempotency_key, type, currency, allow_negative)
                VALUES (?,?,?, 'CUSTOMER','NGN', ?)
                """,
                id, tenant, "acct-" + id, allowNegative);
        db.execute("INSERT INTO balances (account_id, tenant_id) VALUES (?,?)", id, tenant);
    }

    private PostTransactionCommand transfer(String key, long amount) {
        return new PostTransactionCommand(
                tenant, key, "user:ada", "svc:test", "transfer",
                List.of(
                        new EntryLine(settlement, DEBIT, amount, "NGN", null),
                        new EntryLine(customer, CREDIT, amount, "NGN", null)));
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    @Test
    @DisplayName("unpublished events are visible to the gauges, through RLS")
    void gauges_see_unpublished_events() {
        while (relay.relayBatch(500) > 0) {
            // start from a drained queue; the suite shares a database
        }
        posting.post(transfer("tx-1", 500_00));
        // Age the pending row so staleness has something unambiguous to show — a just-written
        // event is legitimately zero seconds old, and zero is exactly the reading under test.
        db.execute(
                "UPDATE outbox_events SET created_at = now() - interval '2 minutes'"
                        + " WHERE tenant_id = ? AND published_at IS NULL",
                tenant);

        // The regression this guards: a gauge that read the table without the relay's transaction
        // scope saw RLS hide every row, and a stalled relay — money events not leaving the ledger
        // — reported healthy forever.
        assertThat(gauge("ledger.outbox.pending"))
                .as("a pending event must register on the scrape, or a dead relay is silent")
                .isGreaterThanOrEqualTo(1.0);
        assertThat(gauge("ledger.outbox.oldest_pending_seconds"))
                .as("the number the 60-second alarm is written against")
                .isGreaterThanOrEqualTo(100.0);
    }

    @Test
    @DisplayName("a drained queue reads zero on both gauges")
    void drained_queue_reads_zero() {
        posting.post(transfer("tx-1", 500_00));
        while (relay.relayBatch(500) > 0) {
            // drain
        }

        assertThat(gauge("ledger.outbox.pending")).isZero();
        assertThat(gauge("ledger.outbox.oldest_pending_seconds"))
                .as("nothing pending means no age, reported as zero rather than an error")
                .isZero();
    }
}
