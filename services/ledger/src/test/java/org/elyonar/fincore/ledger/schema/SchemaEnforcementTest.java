package org.elyonar.fincore.ledger.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.elyonar.fincore.ledger.support.LedgerPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves that each schema rule actually <em>fires</em>, by attempting the violation in raw SQL.
 *
 * <p>{@link SchemaPresenceTest} shows the objects exist. This shows they bite. The distinction
 * matters: a trigger that exists but never rejects anything is worse than no trigger at all,
 * because it reads as protection in a review and in an audit.
 *
 * <p>Every attempt here bypasses application code entirely — that is the point. Tamper evidence
 * that depends on the application being correct is not tamper evidence.
 */
@DisplayName("schema enforcement — the rules reject what they claim to reject")
class SchemaEnforcementTest extends LedgerPostgresTest {

    @Autowired JdbcTemplate jdbc;

    private UUID tenant;
    private UUID account;
    private UUID transaction;

    @BeforeEach
    void seed() {
        tenant = UUID.randomUUID();
        account = UUID.randomUUID();
        transaction = UUID.randomUUID();

        // RLS is a backstop against a *query* that forgot its tenant. These tests are the
        // adversary with direct SQL access, so they run as the table owner and set the context
        // explicitly where a policy would otherwise hide their own fixtures.
        jdbc.execute("SET app.tenant_id = '" + tenant + "'");

        jdbc.update(
                "INSERT INTO currencies (code, minor_unit_exponent, display_name) VALUES (?,?,?)"
                        + " ON CONFLICT (code) DO NOTHING",
                "NGN", 2, "Nigerian Naira");
        jdbc.update(
                "INSERT INTO currencies (code, minor_unit_exponent, display_name) VALUES (?,?,?)"
                        + " ON CONFLICT (code) DO NOTHING",
                "USD", 2, "US Dollar");

        jdbc.update(
                """
                INSERT INTO accounts (id, tenant_id, idempotency_key, type, currency, allow_negative)
                VALUES (?,?,?,'CUSTOMER','NGN',false)
                """,
                account, tenant, "acct-" + account);
        jdbc.update(
                "INSERT INTO balances (account_id, tenant_id) VALUES (?,?)", account, tenant);
        jdbc.update(
                """
                INSERT INTO ledger_transactions
                    (id, tenant_id, idempotency_key, request_fingerprint, initiated_by, executed_by)
                VALUES (?,?,?,'fp','user:test','svc:test')
                """,
                transaction, tenant, "tx-" + transaction);
    }

    private void insertEntry(String direction, long amount, String currency) {
        jdbc.update(
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
        assertThatThrownBy(() -> jdbc.update("UPDATE entries SET amount_minor = 1 WHERE tenant_id = ?", tenant))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("entries cannot be deleted — the audit record is the 7-year record")
    void entries_reject_delete() {
        insertEntry("CREDIT", 100_00, "NGN");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM entries WHERE tenant_id = ?", tenant))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("a negative or zero entry amount is refused")
    void entry_amount_must_be_positive() {
        assertThatThrownBy(() -> insertEntry("CREDIT", 0, "NGN")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertEntry("CREDIT", -1, "NGN")).isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("an entry amount above 10^15 is refused — the cap keeps amounts exact in JSON")
    void entry_amount_is_capped() {
        insertEntry("CREDIT", 1_000_000_000_000_000L, "NGN"); // exactly the cap is allowed
        assertThatThrownBy(() -> insertEntry("CREDIT", 1_000_000_000_000_001L, "NGN"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("an entry in a currency the account is not denominated in is refused")
    void entry_currency_must_match_account() {
        assertThatThrownBy(() -> insertEntry("CREDIT", 100_00, "USD"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("currency mismatch");
    }

    @Test
    @DisplayName("account identity is immutable; status may still change")
    void account_identity_is_immutable() {
        assertThatThrownBy(() -> jdbc.update("UPDATE accounts SET currency='USD' WHERE id=?", account))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("UPDATE accounts SET type='FEE' WHERE id=?", account))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(
                        () -> jdbc.update("UPDATE accounts SET tenant_id=? WHERE id=?", UUID.randomUUID(), account))
                .isInstanceOf(DataAccessException.class);

        // Closure is a legitimate state change and must still work.
        assertThat(jdbc.update(
                        "UPDATE accounts SET status='CLOSED', closed_by='user:ops', closed_at=now() WHERE id=?",
                        account))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("closing an account without attribution is refused")
    void closure_must_be_attributed() {
        assertThatThrownBy(() -> jdbc.update("UPDATE accounts SET status='CLOSED' WHERE id=?", account))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("an original can be reversed only once")
    void one_reversal_per_original() {
        insertReversal(UUID.randomUUID(), "rev-1");
        assertThatThrownBy(() -> insertReversal(UUID.randomUUID(), "rev-2"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("a reversal cannot target another reversal — that resurrects money movement")
    void no_reversal_of_reversal() {
        UUID reversal = UUID.randomUUID();
        insertReversal(reversal, "rev-1");

        assertThatThrownBy(
                        () -> jdbc.update(
                                """
                                INSERT INTO ledger_transactions
                                    (id, tenant_id, idempotency_key, request_fingerprint,
                                     initiated_by, executed_by, reverses_transaction_id)
                                VALUES (?,?,?,'fp','user:test','svc:test',?)
                                """,
                                UUID.randomUUID(), tenant, "rev-of-rev", reversal))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("may not target another reversal");
    }

    @Test
    @DisplayName("a transaction cannot be both a reversal and a compensation")
    void reversal_xor_compensation() {
        assertThatThrownBy(
                        () -> jdbc.update(
                                """
                                INSERT INTO ledger_transactions
                                    (id, tenant_id, idempotency_key, request_fingerprint, initiated_by,
                                     executed_by, reverses_transaction_id, relates_to_transaction_id)
                                VALUES (?,?,?,'fp','user:test','svc:test',?,?)
                                """,
                                UUID.randomUUID(), tenant, "both", transaction, transaction))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("the same idempotency key cannot register two transactions in a tenant")
    void idempotency_key_is_unique_per_tenant() {
        assertThatThrownBy(
                        () -> jdbc.update(
                                """
                                INSERT INTO ledger_transactions
                                    (id, tenant_id, idempotency_key, request_fingerprint, initiated_by, executed_by)
                                VALUES (?,?,?,'fp','user:test','svc:test')
                                """,
                                UUID.randomUUID(), tenant, "tx-" + transaction))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("a hold without an expiry is refused — no permanent lien on customer funds")
    void holds_require_an_expiry() {
        assertThatThrownBy(
                        () -> jdbc.update(
                                """
                                INSERT INTO holds (id, tenant_id, idempotency_key, account_id,
                                                   amount_minor, currency, expires_at)
                                VALUES (?,?,?,?,?, 'NGN', NULL)
                                """,
                                UUID.randomUUID(), tenant, "hold-null-exp", account, 500L))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("a terminal hold never transitions again")
    void terminal_holds_are_terminal() {
        UUID hold = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO holds (id, tenant_id, idempotency_key, account_id, amount_minor,
                                   currency, status, expires_at, resolved_at)
                VALUES (?,?,?,?,?, 'NGN', 'RELEASED', now() + interval '1 day', now())
                """,
                hold, tenant, "hold-" + hold, account, 500L);

        assertThatThrownBy(() -> jdbc.update("UPDATE holds SET status='ACTIVE' WHERE id=?", hold))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("terminal");
    }

    @Test
    @DisplayName("a currency exponent cannot change once an account references it")
    void currency_exponent_is_immutable_in_use() {
        assertThatThrownBy(
                        () -> jdbc.update("UPDATE currencies SET minor_unit_exponent=0 WHERE code='NGN'"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("in use");

        // An unused currency may still be corrected before anyone depends on it.
        jdbc.update(
                "INSERT INTO currencies (code, minor_unit_exponent, display_name) VALUES ('JPY',2,'Yen')"
                        + " ON CONFLICT (code) DO NOTHING");
        assertThat(jdbc.update("UPDATE currencies SET minor_unit_exponent=0 WHERE code='JPY'")).isEqualTo(1);
    }

    @Test
    @DisplayName("a closed accounting period never reopens")
    void periods_never_reopen() {
        jdbc.update(
                "INSERT INTO accounting_periods (tenant_id, period_end, closed_by) VALUES (?, DATE '2026-01-31', ?)",
                tenant, "user:ops");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM accounting_periods WHERE tenant_id=?", tenant))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("tenant config is append-only, so past validation context stays reconstructible")
    void tenant_config_is_append_only() {
        jdbc.update(
                """
                INSERT INTO tenant_config (tenant_id, version, business_timezone, updated_by)
                VALUES (?,1,'Africa/Lagos','user:ops')
                """,
                tenant);
        assertThatThrownBy(
                        () -> jdbc.update("UPDATE tenant_config SET backdate_window_days=90 WHERE tenant_id=?", tenant))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("an entry cannot reference another tenant's account — composite FK refuses it")
    void cross_tenant_reference_is_structurally_impossible() {
        UUID otherTenant = UUID.randomUUID();
        assertThatThrownBy(
                        () -> jdbc.update(
                                """
                                INSERT INTO entries
                                    (transaction_id, account_id, tenant_id, direction, amount_minor,
                                     currency, value_date)
                                VALUES (?,?,?,'CREDIT',100,'NGN',CURRENT_DATE)
                                """,
                                transaction, account, otherTenant))
                .isInstanceOf(DataAccessException.class);
    }

    private void insertReversal(UUID id, String key) {
        jdbc.update(
                """
                INSERT INTO ledger_transactions
                    (id, tenant_id, idempotency_key, request_fingerprint, initiated_by,
                     executed_by, reverses_transaction_id)
                VALUES (?,?,?,'fp','user:test','svc:test',?)
                """,
                id, tenant, key, transaction);
    }
}
