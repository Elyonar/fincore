package org.elyonar.fincore.ledger.period;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.elyonar.fincore.ledger.posting.EntryLine.Direction.CREDIT;
import static org.elyonar.fincore.ledger.posting.EntryLine.Direction.DEBIT;

import java.time.LocalDate;
import java.time.ZoneId;
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
import org.elyonar.fincore.ledger.tenant.TenantConfigService;
import org.elyonar.fincore.ledger.tenant.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("value dating — bounded, governed, and never into a closed period")
class ValueDatingTest extends LedgerPostgresTest {

    @Autowired PostingService posting;
    @Autowired PeriodService periods;
    @Autowired TenantConfigService tenantConfig;
    @Autowired TenantScope tenantScope;
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
                """
                INSERT INTO accounts (id, tenant_id, idempotency_key, type, currency, allow_negative)
                VALUES (?,?,?, 'CUSTOMER','NGN', true)
                """,
                id, tenant, "acct-" + id);
        db.execute("INSERT INTO balances (account_id, tenant_id) VALUES (?,?)", id, tenant);
    }

    private PostTransactionCommand dated(String key, LocalDate valueDate, String reason) {
        return new PostTransactionCommand(
                tenant, key, "user:ada", "svc:test", "dated",
                List.of(
                        new EntryLine(settlement, DEBIT, 100_00, "NGN", valueDate),
                        new EntryLine(customer, CREDIT, 100_00, "NGN", valueDate)),
                null, null, reason);
    }

    @Test
    @DisplayName("an unsupplied value date defaults to the tenant's business date, not the server's")
    void defaults_to_tenant_business_date() {
        posting.post(
                new PostTransactionCommand(
                        tenant, "tx-1", "u", "s", "d",
                        List.of(
                                new EntryLine(settlement, DEBIT, 100_00, "NGN", null),
                                new EntryLine(customer, CREDIT, 100_00, "NGN", null))));

        LocalDate lagosToday = LocalDate.now(ZoneId.of("Africa/Lagos"));
        assertThat(db.count("SELECT count(*) FROM entries WHERE tenant_id = ? AND value_date = ?", tenant, lagosToday))
                .as("a 00:30 WAT deposit belongs to that day's business, not the UTC host's")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a future value date is refused — a ledger records what happened")
    void future_dates_are_refused() {
        assertThatThrownBy(() -> posting.post(dated("tx-future", LocalDate.now().plusDays(1), null)))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).errorCode())
                .isEqualTo(ErrorCode.VALUE_DATE_INVALID);
    }

    @Test
    @DisplayName("backdating without a reason is refused")
    void backdating_requires_a_reason() {
        assertThatThrownBy(() -> posting.post(dated("tx-back", LocalDate.now().minusDays(3), null)))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("require a stated reason");
    }

    @Test
    @DisplayName("backdating within the window, with a reason, is allowed")
    void backdating_within_the_window_is_allowed() {
        LocalDate threeDaysAgo = LocalDate.now(ZoneId.of("Africa/Lagos")).minusDays(3);
        posting.post(dated("tx-back", threeDaysAgo, "late NIBSS settlement file"));

        assertThat(db.count("SELECT count(*) FROM entries WHERE tenant_id = ? AND value_date = ?", tenant, threeDaysAgo))
                .isEqualTo(2);
        assertThat(db.count("SELECT count(*) FROM ledger_transactions WHERE tenant_id = ? AND backdate_reason IS NOT NULL", tenant))
                .as("the reason is recorded, not merely demanded")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("backdating beyond the tenant window is refused")
    void backdating_beyond_the_window_is_refused() {
        assertThatThrownBy(() -> posting.post(dated("tx-old", LocalDate.now().minusDays(400), "very late")))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("backdate window");
    }

    @Test
    @DisplayName("a closed period rejects postings dated inside it")
    void closed_periods_reject_postings() {
        LocalDate fiveDaysAgo = LocalDate.now(ZoneId.of("Africa/Lagos")).minusDays(5);
        periods.close(tenant, fiveDaysAgo, "user:ops");

        assertThatThrownBy(() -> posting.post(dated("tx-closed", fiveDaysAgo, "late file")))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("closed accounting period");

        assertThat(db.count("SELECT count(*) FROM entries WHERE tenant_id = ?", tenant))
                .as("this is what makes a signed-off statement stay signed off")
                .isZero();
    }

    @Test
    @DisplayName("a period after the close date still accepts postings")
    void open_period_still_accepts_postings() {
        periods.close(tenant, LocalDate.now(ZoneId.of("Africa/Lagos")).minusDays(10), "user:ops");

        LocalDate twoDaysAgo = LocalDate.now(ZoneId.of("Africa/Lagos")).minusDays(2);
        posting.post(dated("tx-open", twoDaysAgo, "late file"));

        assertThat(db.count("SELECT count(*) FROM entries WHERE tenant_id = ? AND value_date = ?", tenant, twoDaysAgo))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a period cannot be closed twice, and never reopens")
    void periods_close_once() {
        LocalDate end = LocalDate.now().minusDays(5);
        periods.close(tenant, end, "user:ops");

        assertThatThrownBy(() -> periods.close(tenant, end, "user:ops"))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("already closed");

        assertThat(periods.list(tenant)).hasSize(1);
    }

    @Test
    @DisplayName("tenant config overrides the platform defaults")
    void tenant_config_is_honoured() {
        assertThat(tenantScope.inTenant(tenant, () -> tenantConfig.currentFor(tenant)).backdateWindowDays())
                .as("an unseeded tenant falls back to the documented platform default")
                .isEqualTo(30);

        db.execute(
                """
                INSERT INTO tenant_config (tenant_id, version, business_timezone, backdate_window_days, updated_by)
                VALUES (?, 1, 'Africa/Lagos', 2, 'user:ops')
                """,
                tenant);

        assertThat(tenantScope.inTenant(tenant, () -> tenantConfig.currentFor(tenant)).backdateWindowDays()).isEqualTo(2);

        assertThatThrownBy(() -> posting.post(dated("tx-old", LocalDate.now().minusDays(10), "late")))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("backdate window");
    }

    @Test
    @DisplayName("the newest effective config version wins")
    void newest_effective_version_wins() {
        db.execute(
                """
                INSERT INTO tenant_config (tenant_id, version, business_timezone, backdate_window_days, updated_by)
                VALUES (?, 1, 'Africa/Lagos', 30, 'user:ops'),
                       (?, 2, 'Africa/Lagos', 5,  'user:ops')
                """,
                tenant, tenant);

        assertThat(tenantScope.inTenant(tenant, () -> tenantConfig.currentFor(tenant)).backdateWindowDays()).isEqualTo(5);
    }

    @Test
    @DisplayName("a config version dated in the future is not yet live")
    void future_config_is_not_yet_effective() {
        db.execute(
                """
                INSERT INTO tenant_config (tenant_id, version, business_timezone, backdate_window_days,
                                           effective_from, updated_by)
                VALUES (?, 1, 'Africa/Lagos', 30, now(), 'user:ops'),
                       (?, 2, 'Africa/Lagos', 1,  now() + interval '10 days', 'user:ops')
                """,
                tenant, tenant);

        assertThat(tenantScope.inTenant(tenant, () -> tenantConfig.currentFor(tenant)).backdateWindowDays())
                .as("version orders the history; effective_from decides which row is live")
                .isEqualTo(30);
    }
}
