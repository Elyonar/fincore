package org.elyonar.fincore.ledger.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import javax.sql.DataSource;
import org.elyonar.fincore.ledger.support.LedgerPostgresTest;
import org.elyonar.fincore.ledger.support.TenantSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves each schema rule actually <em>fires</em>, by attempting the violation in raw SQL.
 *
 * <p>{@link SchemaPresenceTest} shows the objects exist. This shows they bite. The distinction is
 * not academic: V1 shipped row-level security that was present, reported enabled, and enforced
 * nothing — see {@link TenantIsolationTest}. A rule that exists but never rejects anything is
 * worse than no rule, because it reads as protection in a review and in an audit.
 *
 * <p>Every attempt here bypasses application code entirely. Tamper evidence that depends on the
 * application being correct is not tamper evidence.
 */
@DisplayName("schema enforcement — the rules reject what they claim to reject")
class SchemaEnforcementTest extends LedgerPostgresTest {

    @Autowired DataSource dataSource;

    private TenantSession db;
    private UUID tenant;
    private UUID account;
    private UUID transaction;

    @BeforeEach
    void seed() {
        tenant = UUID.randomUUID();
        account = UUID.randomUUID();
        transaction = UUID.randomUUID();
        db = TenantSession.open(dataSource, tenant);

        db.execute("INSERT INTO currencies VALUES ('NGN',2,'Naira') ON CONFLICT (code) DO NOTHING");
        db.execute("INSERT INTO currencies VALUES ('USD',2,'Dollar') ON CONFLICT (code) DO NOTHING");
        db.execute(
                """
                INSERT INTO accounts (id, tenant_id, idempotency_key, type, currency, allow_negative)
                VALUES (?,?,?, 'CUSTOMER','NGN', false)
                """,
                account, tenant, "acct-" + account);
        db.execute("INSERT INTO balances (account_id, tenant_id) VALUES (?,?)", account, tenant);
        db.execute(
                """
                INSERT INTO ledger_transactions
                    (id, tenant_id, idempotency_key, request_fingerprint, initiated_by, executed_by)
                VALUES (?,?,?, 'fp','user:test','svc:test')
                """,
                transaction, tenant, "tx-" + transaction);
    }

    @AfterEach
    void closeSession() {
        if (db != null) {
            db.close();
        }
    }

    private void insertEntry(String direction, long amount, String currency) {
        db.execute(
                """
                INSERT INTO entries
                    (transaction_id, account_id, tenant_id, direction, amount_minor, currency, value_date)
                VALUES (?,?,?,?,?,?, CURRENT_DATE)
                """,
                transaction, account, tenant, direction, amount, currency);
    }

    @Test
    @DisplayName("entries cannot be updated — corrections are reversing entries")
    void entries_reject_update() {
        insertEntry("CREDIT", 100_00, "NGN");
        assertThatThrownBy(() -> db.execute("UPDATE entries SET amount_minor = 1"))
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("entries cannot be deleted — they are the seven-year audit record")
    void entries_reject_delete() {
        insertEntry("CREDIT", 100_00, "NGN");
        assertThatThrownBy(() -> db.execute("DELETE FROM entries"))
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("a zero or negative entry amount is refused")
    void entry_amount_must_be_positive() {
        assertThatThrownBy(() -> insertEntry("CREDIT", 0, "NGN")).isInstanceOf(TenantSession.SqlFailure.class);
        assertThatThrownBy(() -> insertEntry("CREDIT", -1, "NGN")).isInstanceOf(TenantSession.SqlFailure.class);
    }

    @Test
    @DisplayName("an amount above 10^15 is refused — the cap keeps amounts exact in JSON")
    void entry_amount_is_capped() {
        insertEntry("CREDIT", 1_000_000_000_000_000L, "NGN"); // the cap itself is allowed
        assertThatThrownBy(() -> insertEntry("CREDIT", 1_000_000_000_000_001L, "NGN"))
                .isInstanceOf(TenantSession.SqlFailure.class);
    }

    @Test
    @DisplayName("an entry in a currency the account is not denominated in is refused")
    void entry_currency_must_match_account() {
        assertThatThrownBy(() -> insertEntry("CREDIT", 100_00, "USD"))
                .hasMessageContaining("currency mismatch");
    }

    @Test
    @DisplayName("account identity is immutable; closure is still permitted")
    void account_identity_is_immutable() {
        assertThatThrownBy(() -> db.execute("UPDATE accounts SET currency='USD' WHERE id=?", account))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> db.execute("UPDATE accounts SET type='FEE' WHERE id=?", account))
                .hasMessageContaining("immutable");

        db.execute(
                "UPDATE accounts SET status='CLOSED', closed_by='user:ops', closed_at=now() WHERE id=?",
                account);
        assertThat(db.count("SELECT count(*) FROM accounts WHERE id=? AND status='CLOSED'", account))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("closing an account without attribution is refused")
    void closure_must_be_attributed() {
        assertThatThrownBy(() -> db.execute("UPDATE accounts SET status='CLOSED' WHERE id=?", account))
                .isInstanceOf(TenantSession.SqlFailure.class);
    }

    @Test
    @DisplayName("an original can be reversed only once")
    void one_reversal_per_original() {
        insertReversal(UUID.randomUUID(), "rev-1");
        assertThatThrownBy(() -> insertReversal(UUID.randomUUID(), "rev-2"))
                .isInstanceOf(TenantSession.SqlFailure.class);
    }

    @Test
    @DisplayName("a reversal cannot target another reversal — that resurrects money movement")
    void no_reversal_of_reversal() {
        UUID reversal = UUID.randomUUID();
        insertReversal(reversal, "rev-1");
        assertThatThrownBy(
                        () -> db.execute(
                                """
                                INSERT INTO ledger_transactions
                                    (id, tenant_id, idempotency_key, request_fingerprint,
                                     initiated_by, executed_by, reverses_transaction_id)
                                VALUES (?,?,?, 'fp','user:test','svc:test', ?)
                                """,
                                UUID.randomUUID(), tenant, "rev-of-rev", reversal))
                .hasMessageContaining("may not target another reversal");
    }

    @Test
    @DisplayName("a transaction cannot be both a reversal and a compensation")
    void reversal_xor_compensation() {
        assertThatThrownBy(
                        () -> db.execute(
                                """
                                INSERT INTO ledger_transactions
                                    (id, tenant_id, idempotency_key, request_fingerprint, initiated_by,
                                     executed_by, reverses_transaction_id, relates_to_transaction_id)
                                VALUES (?,?,?, 'fp','user:test','svc:test', ?, ?)
                                """,
                                UUID.randomUUID(), tenant, "both", transaction, transaction))
                .isInstanceOf(TenantSession.SqlFailure.class);
    }

    @Test
    @DisplayName("one idempotency key registers at most one transaction per tenant")
    void idempotency_key_is_unique_per_tenant() {
        assertThatThrownBy(
                        () -> db.execute(
                                """
                                INSERT INTO ledger_transactions
                                    (id, tenant_id, idempotency_key, request_fingerprint, initiated_by, executed_by)
                                VALUES (?,?,?, 'fp','user:test','svc:test')
                                """,
                                UUID.randomUUID(), tenant, "tx-" + transaction))
                .isInstanceOf(TenantSession.SqlFailure.class);
    }

    @Test
    @DisplayName("a hold without an expiry is refused — no permanent lien on customer funds")
    void holds_require_an_expiry() {
        assertThatThrownBy(
                        () -> db.execute(
                                """
                                INSERT INTO holds (id, tenant_id, idempotency_key, account_id,
                                                   amount_minor, currency, expires_at)
                                VALUES (?,?,?,?,?, 'NGN', NULL)
                                """,
                                UUID.randomUUID(), tenant, "hold-null-exp", account, 500L))
                .isInstanceOf(TenantSession.SqlFailure.class);
    }

    @Test
    @DisplayName("a terminal hold never transitions again")
    void terminal_holds_are_terminal() {
        UUID hold = UUID.randomUUID();
        db.execute(
                """
                INSERT INTO holds (id, tenant_id, idempotency_key, account_id, amount_minor,
                                   currency, status, expires_at, resolved_at)
                VALUES (?,?,?,?,?, 'NGN', 'RELEASED', now() + interval '1 day', now())
                """,
                hold, tenant, "hold-" + hold, account, 500L);

        assertThatThrownBy(() -> db.execute("UPDATE holds SET status='ACTIVE' WHERE id=?", hold))
                .hasMessageContaining("terminal");
    }

    @Test
    @DisplayName("a currency exponent cannot change once an account references it")
    void currency_exponent_is_immutable_in_use() {
        assertThatThrownBy(() -> db.execute("UPDATE currencies SET minor_unit_exponent=0 WHERE code='NGN'"))
                .hasMessageContaining("in use");

        // An unused currency may still be corrected before anything depends on it.
        db.execute("INSERT INTO currencies VALUES ('JPY',2,'Yen') ON CONFLICT (code) DO NOTHING");
        db.execute("UPDATE currencies SET minor_unit_exponent=0 WHERE code='JPY'");
        assertThat(db.count("SELECT count(*) FROM currencies WHERE code='JPY' AND minor_unit_exponent=0"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a closed accounting period never reopens")
    void periods_never_reopen() {
        db.execute(
                "INSERT INTO accounting_periods (tenant_id, period_end, closed_by) VALUES (?, DATE '2026-01-31', ?)",
                tenant, "user:ops");
        assertThatThrownBy(() -> db.execute("DELETE FROM accounting_periods WHERE tenant_id=?", tenant))
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("tenant config is append-only, keeping past validation context reconstructible")
    void tenant_config_is_append_only() {
        db.execute(
                """
                INSERT INTO tenant_config (tenant_id, version, business_timezone, updated_by)
                VALUES (?, 1, 'Africa/Lagos', 'user:ops')
                """,
                tenant);
        assertThatThrownBy(
                        () -> db.execute("UPDATE tenant_config SET backdate_window_days=90 WHERE tenant_id=?", tenant))
                .hasMessageContaining("append-only");
    }

    @org.junit.jupiter.api.Test
    void a_guarded_account_cannot_be_sharded() {
        // The restriction that makes fan-in sharding invariant-neutral (design.md): the
        // negative-balance guard is per-account, so a guarded account split across shards could
        // go negative in aggregate while every shard stays clean. Enforced by trigger since V9.
        assertThatThrownBy(
                        () ->
                                db.execute(
                                        """
                                        INSERT INTO accounts (id, tenant_id, idempotency_key, type, currency,
                                                              group_ref, allow_negative)
                                        VALUES (?,?,?, 'FEE','NGN','fees-pool', false)
                                        """,
                                        UUID.randomUUID(), tenant, "guarded-shard"))
                .hasMessageContaining("must not be sharded");

        // The same shape with allow_negative = true is the sanctioned case and must pass.
        db.execute(
                """
                INSERT INTO accounts (id, tenant_id, idempotency_key, type, currency,
                                      group_ref, allow_negative)
                VALUES (?,?,?, 'FEE','NGN','fees-pool', true)
                """,
                UUID.randomUUID(), tenant, "unguarded-shard");

        // And an update cannot smuggle the forbidden shape in after creation.
        assertThatThrownBy(
                        () -> db.execute("UPDATE accounts SET group_ref='late-shard' WHERE id=?", account))
                .hasMessageContaining("must not be sharded");
    }

    private void insertReversal(UUID id, String key) {
        db.execute(
                """
                INSERT INTO ledger_transactions
                    (id, tenant_id, idempotency_key, request_fingerprint, initiated_by,
                     executed_by, reverses_transaction_id)
                VALUES (?,?,?, 'fp','user:test','svc:test', ?)
                """,
                id, tenant, key, transaction);
    }
}
