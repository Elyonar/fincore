package org.elyonar.fincore.core.orchestration.internal.saga;

import java.util.UUID;
import org.elyonar.fincore.core.orchestration.api.TransferCommand;
import org.elyonar.fincore.core.orchestration.api.TransferResult;
import org.elyonar.fincore.core.product.api.ProductDecision;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The saga's own writes: Phase A's open, Phase C's outcome, and the replay lookup.
 *
 * <p>Plain SQL, for the reasons the design records: the correctness here is a unique index
 * arbitrating duplicates, a reservation and a saga row committing together, and a statement order
 * that is deliberate.
 */
@Repository
public class SagaRecords {

    private final JdbcTemplate jdbc;

    public SagaRecords(@Qualifier("orchestrationJdbcTemplate") JdbcTemplate orchestrationJdbcTemplate) {
        this.jdbc = orchestrationJdbcTemplate;
    }

    private void scopeTo(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config(\'app.tenant_id\', ?, true)", String.class, tenantId.toString());
    }

    /** A saga already recorded under this key, or null. */
    @Transactional(readOnly = true)
    public Existing findByKey(UUID tenantId, String idempotencyKey) {
        scopeTo(tenantId);
        return jdbc.query(
                """
                SELECT id, request_fingerprint, state, amount_minor, fee_minor, currency,
                       product_version, ledger_transaction_id
                  FROM orchestration.sagas
                 WHERE channel_idempotency_key = ?
                """,
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    return new Existing(
                            rs.getString("request_fingerprint"),
                            new TransferResult(
                                    rs.getObject("id", UUID.class),
                                    rs.getString("state"),
                                    rs.getLong("amount_minor"),
                                    rs.getLong("fee_minor"),
                                    rs.getString("currency"),
                                    rs.getInt("product_version"),
                                    rs.getObject("ledger_transaction_id", UUID.class)));
                },
                idempotencyKey);
    }

    /**
     * Phase A: reserve the limit and record the saga, together.
     *
     * <p>One transaction, and the reason all three domains share a database. A reservation that
     * committed without its saga would hold headroom nothing will ever release; a saga that
     * committed without its reservation would let a concurrent transfer breach the limit.
     *
     * <p>The saga is persisted <em>before</em> any outbound call, so a crash in the window between
     * "call the Ledger" and "record that we called" is recoverable rather than an orphan posting.
     */
    @Transactional
    public UUID open(TransferCommand command, ProductDecision decision, String windowKey) {
        scopeTo(command.tenantId());

        UUID sagaId =
                jdbc.queryForObject(
                        """
                        INSERT INTO orchestration.sagas
                            (tenant_id, type, state, channel_idempotency_key, request_fingerprint,
                             subject_customer_id, product_code, product_version, amount_minor,
                             fee_minor, currency, initiated_by, executed_by)
                        VALUES (?, 'TRANSFER', 'POSTING', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """,
                        UUID.class,
                        command.tenantId(),
                        command.idempotencyKey(),
                        command.fingerprint(),
                        command.customerId(),
                        command.productCode(),
                        decision.productVersion(),
                        command.amountMinor(),
                        decision.feeMinor(),
                        command.currency(),
                        command.initiatedBy(),
                        command.executedBy());

        jdbc.update(
                """
                INSERT INTO orchestration.limit_reservations
                    (tenant_id, saga_id, subject_id, limit_type, window_key, amount_minor,
                     currency, expires_at)
                VALUES (?, ?, ?, 'DAILY', ?, ?, ?, now() + INTERVAL '1 day')
                """,
                command.tenantId(),
                sagaId,
                command.customerId(),
                windowKey,
                command.amountMinor() + decision.feeMinor(),
                command.currency());

        return sagaId;
    }

    /** Phase C, success: the saga completes and its reservation is consumed. */
    @Transactional
    public TransferResult complete(UUID tenantId, UUID sagaId, UUID ledgerTransactionId) {
        scopeTo(tenantId);
        jdbc.update(
                """
                UPDATE orchestration.sagas
                   SET state = 'COMPLETED', ledger_transaction_id = ?, terminal_at = now(),
                       claimed_by = NULL, claim_expires_at = NULL
                 WHERE id = ?
                """,
                ledgerTransactionId,
                sagaId);
        jdbc.update(
                "UPDATE orchestration.limit_reservations SET status = 'CONSUMED', resolved_at = now()"
                        + " WHERE saga_id = ?",
                sagaId);
        return readScoped(sagaId);
    }

    /**
     * Phase C, definite failure: the saga fails and its reservation is released.
     *
     * <p>Released only here. Releasing on an unknown would free limit headroom for a transfer that
     * may already have posted.
     */
    @Transactional
    public void fail(UUID tenantId, UUID sagaId, String errorCode) {
        scopeTo(tenantId);
        jdbc.update(
                """
                UPDATE orchestration.sagas
                   SET state = 'FAILED', last_error = ?, terminal_at = now(),
                       claimed_by = NULL, claim_expires_at = NULL
                 WHERE id = ?
                """,
                errorCode,
                sagaId);
        jdbc.update(
                "UPDATE orchestration.limit_reservations SET status = 'RELEASED', resolved_at = now()"
                        + " WHERE saga_id = ?",
                sagaId);
    }

    /**
     * Phase C, unknown: the attempt is logged and nothing else moves.
     *
     * <p>The saga stays POSTING and claimable, the reservation is untouched, and no event is
     * emitted — there is nothing yet to say happened.
     */
    @Transactional
    public void recordUnknownAttempt(UUID tenantId, UUID sagaId, String reason) {
        scopeTo(tenantId);
        jdbc.update(
                """
                INSERT INTO orchestration.saga_attempts (tenant_id, saga_id, attempt_no, outcome, detail)
                SELECT tenant_id, id, attempts + 1, 'UNKNOWN', ? FROM orchestration.sagas WHERE id = ?
                """,
                reason,
                sagaId);
        jdbc.update(
                "UPDATE orchestration.sagas SET attempts = attempts + 1,"
                        + " next_attempt_at = now() + INTERVAL '1 second' WHERE id = ?",
                sagaId);
    }

    /** The saga as a caller sees it. */
    @Transactional(readOnly = true)
    public TransferResult read(UUID tenantId, UUID sagaId) {
        scopeTo(tenantId);
        return readScoped(sagaId);
    }

    /**
     * The same read, for callers already inside a scoped transaction.
     *
     * <p>Separate because the tenant context is {@code SET LOCAL}: it belongs to the transaction,
     * so a method that re-scopes would be wrong to call from within one and a method that does not
     * would be wrong to call from outside. Naming both makes the requirement visible instead of
     * leaving it to be discovered when row-level security hides a row from its own writer.
     */
    private TransferResult readScoped(UUID sagaId) {
        return jdbc.queryForObject(
                """
                SELECT id, state, amount_minor, fee_minor, currency, product_version,
                       ledger_transaction_id
                  FROM orchestration.sagas WHERE id = ?
                """,
                (rs, row) ->
                        new TransferResult(
                                rs.getObject("id", UUID.class),
                                rs.getString("state"),
                                rs.getLong("amount_minor"),
                                rs.getLong("fee_minor"),
                                rs.getString("currency"),
                                rs.getInt("product_version"),
                                rs.getObject("ledger_transaction_id", UUID.class)),
                sagaId);
    }

    /** A saga found by its idempotency key, with the fingerprint that decides replay vs 409. */
    public record Existing(String fingerprint, TransferResult result) {}
}
