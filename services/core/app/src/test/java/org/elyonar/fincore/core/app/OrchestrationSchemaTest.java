package org.elyonar.fincore.core.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The orchestration schema exists, and it bites.
 *
 * <p>Two claims, and they are different: a migration that quietly drops a trigger leaves a schema
 * that still <em>exists</em> while enforcing nothing. So each rule the design leans on is exercised
 * here by attempting the thing it forbids and requiring the database to refuse — not by reading the
 * catalog and trusting it.
 *
 * <p>Real PostgreSQL, never an in-memory substitute. The behaviour under test is PostgreSQL's.
 */
@SpringBootTest
class OrchestrationSchemaTest {

    @Autowired private JdbcTemplate jdbc;

    private UUID tenant() {
        UUID tenantId = UUID.randomUUID();
        jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
        return tenantId;
    }

    // ---------------------------------------------------------------- presence

    @Test
    void every_documented_table_exists() {
        List<String> tables =
                jdbc.queryForList(
                        "SELECT table_name FROM information_schema.tables"
                                + " WHERE table_schema = 'orchestration' AND table_type = 'BASE TABLE'"
                                + " ORDER BY table_name",
                        String.class);

        assertThat(tables)
                .containsExactlyInAnyOrder(
                        "approvals",
                        "limit_reservations",
                        "ops_cases",
                        "outbox_events",
                        "saga_attempts",
                        "sagas",
                        "schema_history",
                        "tills");
    }

    @Test
    void row_level_security_is_enabled_and_forced_on_every_tenant_table() {
        // Enabled is not enforced: PostgreSQL exempts a table's owner unless it is also FORCEd,
        // and migrations run as the owner. Both columns matter, so both are asserted.
        List<String> unprotected =
                jdbc.queryForList(
                        """
                        SELECT c.relname
                          FROM pg_class c
                          JOIN pg_namespace n ON n.oid = c.relnamespace
                         WHERE n.nspname = 'orchestration'
                           AND c.relkind = 'r'
                           AND c.relname <> 'schema_history'
                           AND NOT (c.relrowsecurity AND c.relforcerowsecurity)
                        """,
                        String.class);

        assertThat(unprotected).isEmpty();
    }

    @Test
    void the_triggers_the_design_leans_on_exist() {
        List<String> triggers =
                jdbc.queryForList(
                        "SELECT tgname FROM pg_trigger t"
                                + " JOIN pg_class c ON c.oid = t.tgrelid"
                                + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                                + " WHERE n.nspname = 'orchestration' AND NOT t.tgisinternal"
                                + " ORDER BY tgname",
                        String.class);

        assertThat(triggers)
                .contains(
                        "saga_attempts_are_append_only",
                        "sagas_terminal_states_are_terminal",
                        "approvals_are_single_use");
    }

    // ------------------------------------------------------------- enforcement

    @Test
    void a_terminal_saga_cannot_be_reopened() {
        UUID tenantId = tenant();
        UUID sagaId = insertSaga(tenantId, "COMPLETED", UUID.randomUUID());

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE orchestration.sagas SET state = 'FAILED' WHERE id = ?",
                                        sagaId))
                .hasMessageContaining("terminal");
    }

    @Test
    void a_terminal_saga_cannot_have_its_ledger_transaction_rewritten() {
        // The one that matters for reconciliation: if this were editable, a mismatch with the
        // Ledger could be "resolved" by changing our record of what we posted.
        UUID tenantId = tenant();
        UUID sagaId = insertSaga(tenantId, "COMPLETED", UUID.randomUUID());

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE orchestration.sagas SET ledger_transaction_id = ? WHERE id = ?",
                                        UUID.randomUUID(),
                                        sagaId))
                .hasMessageContaining("terminal");
    }

    @Test
    void attempt_history_cannot_be_edited_or_deleted() {
        UUID tenantId = tenant();
        UUID sagaId = insertSaga(tenantId, "POSTING", null);
        jdbc.update(
                "INSERT INTO orchestration.saga_attempts (tenant_id, saga_id, attempt_no, outcome)"
                        + " VALUES (?,?,1,'UNKNOWN')",
                tenantId,
                sagaId);

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE orchestration.saga_attempts SET outcome = 'SUCCESS'"
                                                + " WHERE saga_id = ?",
                                        sagaId))
                .hasMessageContaining("append-only");

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "DELETE FROM orchestration.saga_attempts WHERE saga_id = ?",
                                        sagaId))
                .hasMessageContaining("append-only");
    }

    @Test
    void a_completed_saga_must_name_the_transaction_it_posted() {
        UUID tenantId = tenant();
        assertThatThrownBy(() -> insertSaga(tenantId, "COMPLETED", null))
                .hasMessageContaining("completed_has_a_ledger_transaction");
    }

    @Test
    void a_failed_saga_must_not_name_one() {
        UUID tenantId = tenant();
        assertThatThrownBy(() -> insertSaga(tenantId, "FAILED", UUID.randomUUID()))
                .hasMessageContaining("failed_has_no_ledger_transaction");
    }

    @Test
    void an_approval_cannot_name_the_same_person_as_maker_and_checker() {
        UUID tenantId = tenant();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                                        INSERT INTO orchestration.approvals
                                            (tenant_id, action, target_saga_id, amount_minor,
                                             status, made_by, checked_by, checked_at)
                                        VALUES (?, 'REVERSAL', ?, 1000, 'APPROVED', 'user:ada', 'user:ada', now())
                                        """,
                                        tenantId,
                                        UUID.randomUUID()))
                .hasMessageContaining("checker_differs_from_maker");
    }

    @Test
    void a_spent_approval_cannot_be_returned_to_an_unspent_state() {
        // Otherwise one approval authorizes a second reversal of the same transaction — a double
        // credit with an audit trail that looks impeccable.
        UUID tenantId = tenant();
        UUID approvalId =
                jdbc.queryForObject(
                        """
                        INSERT INTO orchestration.approvals
                            (tenant_id, action, target_saga_id, amount_minor, status,
                             made_by, checked_by, checked_at, consumed_at)
                        VALUES (?, 'REVERSAL', ?, 1000, 'CONSUMED', 'user:ada', 'user:tobi', now(), now())
                        RETURNING id
                        """,
                        UUID.class,
                        tenantId,
                        UUID.randomUUID());

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE orchestration.approvals SET status = 'APPROVED' WHERE id = ?",
                                        approvalId))
                .hasMessageContaining("spent");
    }

    @Test
    void a_duplicate_channel_key_is_refused_by_the_index_not_by_code() {
        UUID tenantId = tenant();
        insertSagaWithKey(tenantId, "dup-key-1");

        assertThatThrownBy(() -> insertSagaWithKey(tenantId, "dup-key-1"))
                .hasMessageContaining("sagas_idempotent");
    }

    @Test
    void a_non_reversal_may_not_carry_an_approval() {
        UUID tenantId = tenant();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                                        INSERT INTO orchestration.sagas
                                            (tenant_id, type, state, channel_idempotency_key,
                                             request_fingerprint, amount_minor, currency,
                                             initiated_by, executed_by, approval_id)
                                        VALUES (?, 'TRANSFER', 'RECEIVED', ?, 'fp', 1000, 'NGN',
                                                'user:ada', 'core', ?)
                                        """,
                                        tenantId,
                                        UUID.randomUUID().toString(),
                                        UUID.randomUUID()))
                .hasMessageContaining("reversal_shape");
    }

    // ------------------------------------------------------------------ helpers

    private UUID insertSaga(UUID tenantId, String state, UUID ledgerTransactionId) {
        return jdbc.queryForObject(
                """
                INSERT INTO orchestration.sagas
                    (tenant_id, type, state, channel_idempotency_key, request_fingerprint,
                     amount_minor, currency, initiated_by, executed_by,
                     ledger_transaction_id, terminal_at)
                VALUES (?, 'TRANSFER', ?, ?, 'fp', 1000, 'NGN', 'user:ada', 'core', ?,
                        CASE WHEN ? IN ('COMPLETED','FAILED') THEN now() ELSE NULL END)
                RETURNING id
                """,
                UUID.class,
                tenantId,
                state,
                UUID.randomUUID().toString(),
                ledgerTransactionId,
                state);
    }

    private void insertSagaWithKey(UUID tenantId, String key) {
        jdbc.update(
                """
                INSERT INTO orchestration.sagas
                    (tenant_id, type, state, channel_idempotency_key, request_fingerprint,
                     amount_minor, currency, initiated_by, executed_by)
                VALUES (?, 'TRANSFER', 'RECEIVED', ?, 'fp', 1000, 'NGN', 'user:ada', 'core')
                """,
                tenantId,
                key);
    }
}
