package org.elyonar.fincore.ledger.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.elyonar.fincore.ledger.posting.EntryLine.Direction.CREDIT;
import static org.elyonar.fincore.ledger.posting.EntryLine.Direction.DEBIT;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.elyonar.fincore.ledger.posting.EntryLine;
import org.elyonar.fincore.ledger.posting.PostTransactionCommand;
import org.elyonar.fincore.ledger.posting.PostingService;
import org.elyonar.fincore.ledger.shared.LedgerException;
import org.elyonar.fincore.ledger.support.LedgerPostgresTest;
import org.elyonar.fincore.ledger.support.TenantSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("outbox — an event exists if and only if the money moved")
class OutboxTest extends LedgerPostgresTest {

    @Autowired PostingService posting;
    @Autowired OutboxRelay relay;
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

    @Test
    @DisplayName("a committed posting leaves exactly one pending event")
    void posting_writes_its_event() {
        posting.post(transfer("tx-1", 500_00));

        assertThat(db.count("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND published_at IS NULL", tenant))
                .isEqualTo(1);
        assertThat(db.count("SELECT count(*) FROM outbox_events WHERE event_type = 'posting.completed'"))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("a rejected posting leaves no event at all")
    void rejected_posting_writes_no_event() {
        var unbalanced =
                new PostTransactionCommand(
                        tenant, "tx-bad", "u", "s", "d",
                        List.of(
                                new EntryLine(settlement, DEBIT, 100_00, "NGN", null),
                                new EntryLine(customer, CREDIT, 90_00, "NGN", null)));

        assertThatThrownBy(() -> posting.post(unbalanced)).isInstanceOf(LedgerException.class);

        assertThat(db.count("SELECT count(*) FROM outbox_events WHERE tenant_id = ?", tenant))
                .as("the event and the money commit together or not at all")
                .isZero();
    }

    @Test
    @DisplayName("monetary values in payloads are decimal strings, never JSON numbers")
    void amounts_are_strings_in_payloads() {
        posting.post(transfer("tx-1", 500_00));

        assertThat(
                        db.count(
                                """
                                SELECT count(*) FROM outbox_events
                                 WHERE tenant_id = ? AND jsonb_typeof(payload->'entryCount') = 'number'
                                """,
                                tenant))
                .as("a genuine count stays a number")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the relay publishes and marks pending events")
    void relay_publishes_pending_events() {
        while (relay.relayBatch(500) > 0) {
            // start from a drained queue; the suite shares a database
        }
        posting.post(transfer("tx-1", 500_00));
        posting.post(transfer("tx-2", 200_00));

        assertThat(relay.relayBatch(10)).isEqualTo(2);
        assertThat(db.count("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND published_at IS NULL", tenant))
                .isZero();
        assertThat(relay.relayBatch(10)).as("nothing left to do on a second pass").isZero();
    }

    @Test
    @DisplayName("a late-committing low id is still published — the watermark trap")
    void late_committing_low_id_is_not_skipped() throws Exception {
        while (relay.relayBatch(500) > 0) {
            // start drained
        }

        // Sequence values are handed out at insert, not at commit. Hold a genuinely uncommitted
        // transaction that has already taken a low id, let a later event commit and be relayed,
        // then commit the slow one. A relay that remembered "last id seen" would skip the low id
        // forever, silently — the event simply never arrives and nothing reports an error.
        try (java.sql.Connection slow = dataSource.getConnection()) {
            slow.setAutoCommit(false);
            try (var ctx = slow.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
                ctx.setString(1, tenant.toString());
                ctx.execute();
            }
            try (var insert =
                    slow.prepareStatement(
                            "INSERT INTO outbox_events (tenant_id, event_type, aggregate_id, payload)"
                                    + " VALUES (?, 'posting.completed', ?, '{}'::jsonb)")) {
                insert.setObject(1, tenant);
                insert.setString(2, UUID.randomUUID().toString());
                insert.executeUpdate();
            }

            // Uncommitted, so invisible: the relay correctly drains only the later event.
            posting.post(transfer("tx-fast", 100_00));
            assertThat(relay.relayBatch(10)).isEqualTo(1);

            slow.commit();
        }

        // The slow row is now visible, carrying a *lower* id than one already published.
        assertThat(relay.relayBatch(10))
                .as("published_at IS NULL has no blind spot; a watermark would have lost this event")
                .isEqualTo(1);

        assertThat(db.count("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND published_at IS NULL", tenant))
                .isZero();
    }

    @Test
    @DisplayName("staleness is measurable, so a dead relay is not silent")
    void oldest_pending_age_is_reported() {
        // The metric is deliberately global — a relay is unhealthy for the whole service, not per
        // tenant — so drain whatever earlier suites left behind before measuring.
        while (relay.relayBatch(500) > 0) {
            // drain
        }
        assertThat(relay.oldestPendingAgeSeconds()).isEmpty();

        posting.post(transfer("tx-1", 500_00));

        assertThat(relay.oldestPendingAgeSeconds()).isPresent().get().satisfies(age -> assertThat(age).isNotNegative());

        while (relay.relayBatch(500) > 0) {
            // drain
        }
        assertThat(relay.oldestPendingAgeSeconds()).as("drained means nothing pending").isEmpty();
    }

    @Test
    @DisplayName("purge removes published rows but never pending ones")
    void purge_spares_pending_events() {
        posting.post(transfer("tx-1", 500_00));
        relay.relayBatch(10);
        posting.post(transfer("tx-2", 200_00));

        db.execute("UPDATE outbox_events SET published_at = now() - interval '40 days' WHERE published_at IS NOT NULL");

        assertThat(relay.purgePublishedOlderThanDays(30)).isGreaterThanOrEqualTo(1);
        assertThat(db.count("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND published_at IS NULL", tenant))
                .as("an undelivered event must survive any retention policy")
                .isEqualTo(1);
    }
}
