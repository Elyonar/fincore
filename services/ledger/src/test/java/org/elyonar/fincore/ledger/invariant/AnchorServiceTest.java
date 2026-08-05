package org.elyonar.fincore.ledger.invariant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.elyonar.fincore.ledger.posting.EntryLine.Direction.CREDIT;
import static org.elyonar.fincore.ledger.posting.EntryLine.Direction.DEBIT;

import java.time.LocalDate;
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

@DisplayName("anchors — verification that stays affordable in year seven")
class AnchorServiceTest extends LedgerPostgresTest {

    @Autowired AnchorService anchors;
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
        account(customer);
        account(settlement);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private void account(UUID id) {
        db.execute(
                "INSERT INTO accounts (id, tenant_id, idempotency_key, type, currency, allow_negative)"
                        + " VALUES (?,?,?, 'CUSTOMER','NGN', true)",
                id, tenant, "acct-" + id);
        db.execute("INSERT INTO balances (account_id, tenant_id) VALUES (?,?)", id, tenant);
    }

    private void post(String key, long amount, LocalDate valueDate, String reason) {
        posting.post(
                new PostTransactionCommand(
                        tenant, key, "u", "s", "d",
                        List.of(
                                new EntryLine(settlement, DEBIT, amount, "NGN", valueDate),
                                new EntryLine(customer, CREDIT, amount, "NGN", valueDate)),
                        null, null, reason));
    }

    @Test
    @DisplayName("an anchor records the balance proven at its bound")
    void anchor_records_a_proven_balance() {
        post("tx-1", 500_00, null, null);

        assertThat(anchors.captureFor(tenant, LocalDate.now())).isEqualTo(2);

        assertThat(db.count("SELECT balance_minor FROM balance_anchors WHERE account_id = ?", customer))
                .isEqualTo(500_00);
        assertThat(anchors.verifyIncrementally(tenant)).isEmpty();
    }

    @Test
    @DisplayName("anchor plus later entries equals the current balance")
    void incremental_check_uses_only_the_delta() {
        post("tx-1", 500_00, null, null);
        anchors.captureFor(tenant, LocalDate.now());

        post("tx-2", 250_00, null, null);

        assertThat(anchors.verifyIncrementally(tenant))
                .as("the check sums only what was written since the anchor")
                .isEmpty();
        assertThat(db.count("SELECT current_minor FROM balances WHERE account_id = ?", customer))
                .isEqualTo(750_00);
    }

    @Test
    @DisplayName("a balance that drifts from anchor plus delta is caught")
    void drift_from_anchor_is_caught() {
        post("tx-1", 500_00, null, null);
        anchors.captureFor(tenant, LocalDate.now());
        post("tx-2", 250_00, null, null);

        db.execute("UPDATE balances SET current_minor = current_minor + 999 WHERE account_id = ?", customer);

        assertThat(anchors.verifyIncrementally(tenant))
                .singleElement()
                .satisfies(
                        f -> {
                            assertThat(f.invariant()).isEqualTo("anchor_plus_delta_matches_balance");
                            assertThat(f.detail()).contains("75000").contains("75999");
                        });
    }

    @Test
    @DisplayName("a backdated posting cannot falsify an anchor already taken")
    void backdating_cannot_falsify_an_anchor() {
        post("tx-1", 500_00, null, null);
        anchors.captureFor(tenant, LocalDate.now());
        long anchoredBalance =
                db.count("SELECT balance_minor FROM balance_anchors WHERE account_id = ?", customer);

        // Dated into the past, but written now — so it has a higher entry id than the anchor's
        // bound and lands in the delta window. A value-date-keyed anchor would have been wrong.
        post("tx-backdated", 300_00, LocalDate.now().minusDays(5), "late settlement file");

        assertThat(db.count("SELECT balance_minor FROM balance_anchors WHERE account_id = ?", customer))
                .as("the anchor is untouched by a posting dated before it")
                .isEqualTo(anchoredBalance);
        assertThat(anchors.verifyIncrementally(tenant))
                .as("and the incremental check still reconciles, because id order is what it uses")
                .isEmpty();
    }

    @Test
    @DisplayName("capture is idempotent within a day")
    void capture_is_idempotent_per_day() {
        post("tx-1", 500_00, null, null);
        LocalDate today = LocalDate.now();

        assertThat(anchors.captureFor(tenant, today)).isEqualTo(2);
        post("tx-2", 250_00, null, null);

        assertThat(anchors.captureFor(tenant, today))
                .as("re-proving at a later bound would change what earlier checks relied on")
                .isZero();
        assertThat(db.count("SELECT balance_minor FROM balance_anchors WHERE account_id = ?", customer))
                .isEqualTo(500_00);
    }

    @Test
    @DisplayName("an anchor cannot be edited or deleted")
    void anchors_are_immutable() {
        post("tx-1", 500_00, null, null);
        anchors.captureFor(tenant, LocalDate.now());

        assertThatThrownBy(() -> db.execute("UPDATE balance_anchors SET balance_minor = 1 WHERE tenant_id = ?", tenant))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> db.execute("DELETE FROM balance_anchors WHERE tenant_id = ?", tenant))
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("anchors are tenant-scoped like everything else")
    void anchors_are_tenant_scoped() {
        post("tx-1", 500_00, null, null);
        anchors.captureFor(tenant, LocalDate.now());

        UUID otherTenant = UUID.randomUUID();
        try (TenantSession theirs = TenantSession.open(dataSource, otherTenant)) {
            assertThat(theirs.count("SELECT count(*) FROM balance_anchors")).isZero();
        }
    }
}
