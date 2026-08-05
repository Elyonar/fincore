package org.elyonar.fincore.ledger.invariant;

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
import org.elyonar.fincore.ledger.posting.ReversalService;
import org.elyonar.fincore.ledger.posting.ReverseTransactionCommand;
import org.elyonar.fincore.ledger.support.LedgerPostgresTest;
import org.elyonar.fincore.ledger.support.TenantSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("invariants — the ledger's argument for its own correctness")
class InvariantServiceTest extends LedgerPostgresTest {

    @Autowired InvariantService invariants;
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
        account(customer, false);
        account(settlement, true);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private void account(UUID id, boolean allowNegative) {
        db.execute(
                "INSERT INTO accounts (id, tenant_id, idempotency_key, type, currency, allow_negative)"
                        + " VALUES (?,?,?, 'CUSTOMER','NGN', ?)",
                id, tenant, "acct-" + id, allowNegative);
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

    @Test
    @DisplayName("a healthy ledger reports clean")
    void healthy_ledger_is_clean() {
        credit("tx-1", 500_00);
        credit("tx-2", 250_00);

        InvariantReport report = invariants.verify(tenant);

        assertThat(report.clean()).isTrue();
        assertThat(report.violations()).isZero();
        assertThat(report.runId()).as("every run is recorded, clean or not").isNotNull();
    }

    @Test
    @DisplayName("a tampered balance is caught, with the counterexample")
    void tampered_balance_is_caught() {
        credit("tx-1", 500_00);

        // Balances are a cache of the entries. Corrupt the cache directly — entries themselves are
        // append-only and cannot be edited at all.
        db.execute("UPDATE balances SET current_minor = current_minor + 45000 WHERE account_id = ?", customer);

        InvariantReport report = invariants.verify(tenant);

        assertThat(report.clean()).isFalse();
        assertThat(report.findings())
                .anySatisfy(
                        f -> {
                            assertThat(f.kind()).isEqualTo(Finding.Kind.VIOLATION);
                            assertThat(f.invariant()).isEqualTo("balance_matches_entries");
                            assertThat(f.detail())
                                    .as("a counterexample, not merely 'something is wrong'")
                                    .contains("95000")
                                    .contains("50000");
                        });
    }

    @Test
    @DisplayName("money that does not conserve is caught per currency")
    void unconserved_money_is_caught() {
        credit("tx-1", 500_00);
        UUID tx = UUID.randomUUID();
        db.execute(
                "INSERT INTO ledger_transactions (id, tenant_id, idempotency_key, request_fingerprint,"
                        + " initiated_by, executed_by) VALUES (?,?,?, 'fp','u','s')",
                tx, tenant, "orphan");
        db.execute(
                "INSERT INTO entries (transaction_id, account_id, tenant_id, direction, amount_minor,"
                        + " currency, value_date) VALUES (?,?,?, 'CREDIT', 1000, 'NGN', CURRENT_DATE)",
                tx, customer, tenant);

        InvariantReport report = invariants.verify(tenant);

        assertThat(report.findings())
                .anySatisfy(f -> assertThat(f.invariant()).isEqualTo("money_is_conserved"));
    }

    @Test
    @DisplayName("a holds_total that disagrees with active holds is caught")
    void drifted_holds_total_is_caught() {
        credit("tx-1", 500_00);
        db.execute("UPDATE balances SET holds_total_minor = 12345 WHERE account_id = ?", customer);

        InvariantReport report = invariants.verify(tenant);

        assertThat(report.findings()).anySatisfy(f -> assertThat(f.invariant()).isEqualTo("holds_add_up"));
    }

    @Test
    @DisplayName("a reversal-caused negative is authorized exposure, not a violation")
    void reversal_negative_is_exposure_not_violation() {
        UUID original = credit("tx-1", 500_00);
        posting.post(
                new PostTransactionCommand(
                        tenant, "spend", "u", "s", "onward",
                        List.of(
                                new EntryLine(customer, DEBIT, 500_00, "NGN", null),
                                new EntryLine(settlement, CREDIT, 500_00, "NGN", null))));
        reversals.reverse(new ReverseTransactionCommand(tenant, original, "rev-1", "user:ops", "svc:test"));

        InvariantReport report = invariants.verify(tenant);

        assertThat(report.clean())
                .as("routine reversals must not trip the alarm, or nobody will trust it")
                .isTrue();
        assertThat(report.exposures()).isEqualTo(1);
        assertThat(report.findings())
                .anySatisfy(
                        f -> {
                            assertThat(f.kind()).isEqualTo(Finding.Kind.AUTHORIZED_EXPOSURE);
                            assertThat(f.detail()).contains("caused by a reversal");
                        });
    }

    @Test
    @DisplayName("a negative with no reversal behind it is a violation")
    void unexplained_negative_is_a_violation() {
        credit("tx-1", 500_00);
        db.execute("UPDATE balances SET current_minor = -1 WHERE account_id = ?", customer);

        InvariantReport report = invariants.verify(tenant);

        assertThat(report.findings())
                .anySatisfy(
                        f -> {
                            assertThat(f.invariant()).isEqualTo("no_unexplained_negatives");
                            assertThat(f.kind())
                                    .as("no reversal in its causal chain, so this is a bug")
                                    .isEqualTo(Finding.Kind.VIOLATION);
                        });
        assertThat(report.clean()).isFalse();
    }

    @Test
    @DisplayName("an inconsistent terminal hold cannot be created at all")
    void terminal_hold_inconsistency_is_structurally_impossible() {
        db.execute(
                "INSERT INTO holds (id, tenant_id, idempotency_key, account_id, amount_minor, currency,"
                        + " status, expires_at) VALUES (?,?,?,?,?, 'NGN','ACTIVE', now() + interval '1 day')",
                UUID.randomUUID(), tenant, "h-1", settlement, 100L);
        // The fixture has to be a ledger that is otherwise healthy, or the other invariants fire
        // on the fixture rather than on what this test is about. Both duly did on earlier
        // attempts: holds_total had to be kept in step, and the hold had to sit on an unguarded
        // account, since reserving more than is available is exactly what HoldService refuses.
        db.execute(
                "UPDATE balances SET holds_total_minor = holds_total_minor + 100 WHERE account_id = ?",
                settlement);

        // The invariant exists to catch a hold that is terminal without a resolution timestamp.
        // It turns out that state cannot be reached even from raw SQL: holds_resolution_consistent
        // refuses it. That is the stronger guarantee — a constraint prevents, an invariant only
        // reports — so the test asserts prevention rather than detection.
        assertThatThrownBy(
                        () ->
                                db.execute(
                                        "UPDATE holds SET status='RELEASED'"
                                                + " WHERE tenant_id = ? AND idempotency_key = 'h-1'",
                                        tenant))
                .isInstanceOf(TenantSession.SqlFailure.class)
                .hasMessageContaining("holds_resolution_consistent");

        // The invariant stays as defence in depth: a future migration that dropped the constraint
        // would leave the check as the only thing still looking.
        assertThat(invariants.verify(tenant).clean()).isTrue();
    }

    @Test
    @DisplayName("the latest report is fetchable without triggering a scan")
    void latest_report_is_fetchable() {
        credit("tx-1", 500_00);
        InvariantReport run = invariants.verify(tenant);

        InvariantReport latest = invariants.latest(tenant);

        assertThat(latest).isNotNull();
        assertThat(latest.runId()).isEqualTo(run.runId());
        assertThat(latest.scope()).isEqualTo("INCREMENTAL");
    }

    @Test
    @DisplayName("one tenant's findings never appear in another's report")
    void reports_are_tenant_scoped() {
        credit("tx-1", 500_00);
        db.execute("UPDATE balances SET current_minor = 999999 WHERE account_id = ?", customer);
        invariants.verify(tenant);

        UUID otherTenant = UUID.randomUUID();
        InvariantReport theirs = invariants.verify(otherTenant);

        assertThat(theirs.clean()).isTrue();
        assertThat(theirs.findings()).isEmpty();
        assertThat(invariants.latest(otherTenant).runId()).isNotEqualTo(invariants.latest(tenant).runId());
    }
}
