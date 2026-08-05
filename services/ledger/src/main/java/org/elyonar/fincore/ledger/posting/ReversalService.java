package org.elyonar.fincore.ledger.posting;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.elyonar.fincore.ledger.shared.ErrorCode;
import org.elyonar.fincore.ledger.shared.LedgerException;
import org.elyonar.fincore.ledger.tenant.TenantScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Undoes a posted transaction by mirroring its entries.
 *
 * <p>Nothing is ever edited or deleted to undo money movement: the original entries stay exactly
 * as written and a second, opposing transaction is recorded. That is what makes the audit trail a
 * record of what happened rather than of what someone later wished had happened.
 *
 * <p>Reversal deliberately bypasses two guards that apply to every ordinary posting — the
 * negative-balance guard and the closed-account check. Undoing must always be possible, including
 * into an account that has been closed since, and including where the money has already been spent
 * onward. Both bypasses are recorded and surface in the authorized-exposure report rather than as
 * invariant violations, which is what keeps the invariant alarm meaningful: a violation page always
 * means bug.
 */
@Service
public class ReversalService {

    private final TenantScope tenantScope;
    private final JdbcTemplate jdbc;

    public ReversalService(TenantScope tenantScope, JdbcTemplate jdbc) {
        this.tenantScope = tenantScope;
        this.jdbc = jdbc;
    }

    public PostingResult reverse(ReverseTransactionCommand command) {
        return tenantScope.inTenant(command.tenantId(), () -> doReverse(command));
    }

    private PostingResult doReverse(ReverseTransactionCommand command) {
        // A reversal is itself an idempotent creating operation, with its own key.
        var existing =
                jdbc.query(
                        "SELECT id FROM ledger_transactions WHERE tenant_id = ? AND idempotency_key = ?",
                        rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                        command.tenantId(),
                        command.idempotencyKey());
        if (existing != null) {
            return PostingResult.replay(existing);
        }

        // Tier 1, before any balance row. A compensation posting takes this same lock first, so
        // the two serialise against each other instead of deadlocking.
        lockTransaction(command.tenantId(), command.originalTransactionId());

        var original =
                jdbc.query(
                        """
                        SELECT status, reverses_transaction_id
                          FROM ledger_transactions WHERE tenant_id = ? AND id = ?
                        """,
                        rs -> rs.next() ? new Object[] {rs.getString(1), rs.getObject(2, UUID.class)} : null,
                        command.tenantId(),
                        command.originalTransactionId());
        if (original == null) {
            throw new LedgerException(
                    ErrorCode.ACCOUNT_NOT_FOUND, "unknown transaction " + command.originalTransactionId());
        }

        if ("REVERSED".equals(original[0])) {
            // Carry the winner's id so a saga converges on it rather than retry-looping against a
            // state that will never change again.
            UUID winner =
                    jdbc.queryForObject(
                            "SELECT id FROM ledger_transactions WHERE tenant_id = ? AND reverses_transaction_id = ?",
                            UUID.class,
                            command.tenantId(),
                            command.originalTransactionId());
            throw new AlreadyReversedException(winner);
        }

        if (original[1] != null) {
            // Schema-enforced too; checked here so the caller gets the contract code rather than a
            // constraint violation.
            throw new LedgerException(
                    ErrorCode.REVERSAL_OF_REVERSAL,
                    "target is itself a reversal; corrections beyond one undo are fresh transactions");
        }

        long compensations =
                jdbc.queryForObject(
                        "SELECT count(*) FROM ledger_transactions WHERE tenant_id = ? AND relates_to_transaction_id = ?",
                        Long.class,
                        command.tenantId(),
                        command.originalTransactionId());
        if (compensations > 0) {
            // A partial refund has already been given. A full reversal on top of it would credit
            // the customer twice for one mistake.
            throw new LedgerException(
                    ErrorCode.HAS_COMPENSATIONS,
                    "target has linked compensations; resolve via ops rather than a plain reversal");
        }

        List<EntryLine> mirrored = mirrorOf(command.tenantId(), command.originalTransactionId());
        if (mirrored.isEmpty()) {
            throw new LedgerException(
                    ErrorCode.UNBALANCED, "transaction " + command.originalTransactionId() + " has no entries");
        }

        UUID reversalId = UUID.randomUUID();
        int registered =
                jdbc.update(
                        """
                        INSERT INTO ledger_transactions
                            (id, tenant_id, idempotency_key, request_fingerprint, initiated_by,
                             executed_by, reverses_transaction_id)
                        VALUES (?,?,?,?,?,?,?)
                        ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                        """,
                        reversalId,
                        command.tenantId(),
                        command.idempotencyKey(),
                        "reversal:" + command.originalTransactionId(),
                        command.initiatedBy(),
                        command.executedBy(),
                        command.originalTransactionId());
        if (registered == 0) {
            UUID winner =
                    jdbc.queryForObject(
                            "SELECT id FROM ledger_transactions WHERE tenant_id = ? AND idempotency_key = ?",
                            UUID.class,
                            command.tenantId(),
                            command.idempotencyKey());
            return PostingResult.replay(winner);
        }

        // Tier 2, sorted, exactly as a posting does.
        Map<UUID, Long> deltas = new LinkedHashMap<>();
        for (EntryLine entry : mirrored) {
            deltas.merge(entry.accountId(), entry.signedMinor(), Long::sum);
        }
        List<UUID> lockOrder = deltas.keySet().stream().sorted(Comparator.naturalOrder()).toList();
        for (UUID accountId : lockOrder) {
            lockBalance(command.tenantId(), accountId);
        }

        // Mirrored entries carry the *current* business date, never the original's: posting into
        // the past would rewrite a period that may already be closed and signed off. The link to
        // the original preserves traceability instead.
        LocalDate businessDate = LocalDate.now();
        for (EntryLine entry : mirrored) {
            jdbc.update(
                    """
                    INSERT INTO entries
                        (transaction_id, account_id, tenant_id, direction, amount_minor, currency, value_date)
                    VALUES (?,?,?,?,?,?,?)
                    """,
                    reversalId,
                    entry.accountId(),
                    command.tenantId(),
                    entry.direction().name(),
                    entry.amountMinor(),
                    entry.currency(),
                    businessDate);
        }

        // No negative-balance guard and no closed-account check: see the class comment.
        for (UUID accountId : lockOrder) {
            jdbc.update(
                    """
                    UPDATE balances SET current_minor = current_minor + ?, updated_at = now()
                     WHERE tenant_id = ? AND account_id = ?
                    """,
                    deltas.get(accountId),
                    command.tenantId(),
                    accountId);
        }

        jdbc.update(
                "UPDATE ledger_transactions SET status='REVERSED' WHERE tenant_id = ? AND id = ?",
                command.tenantId(),
                command.originalTransactionId());

        return PostingResult.posted(reversalId);
    }

    /** The original's entries, each with its direction flipped. */
    private List<EntryLine> mirrorOf(UUID tenantId, UUID transactionId) {
        List<EntryLine> mirrored = new ArrayList<>();
        jdbc.query(
                """
                SELECT account_id, direction, amount_minor, currency
                  FROM entries WHERE tenant_id = ? AND transaction_id = ? ORDER BY id
                """,
                rs -> {
                    EntryLine.Direction opposite =
                            "DEBIT".equals(rs.getString(2))
                                    ? EntryLine.Direction.CREDIT
                                    : EntryLine.Direction.DEBIT;
                    mirrored.add(
                            new EntryLine(
                                    rs.getObject(1, UUID.class),
                                    opposite,
                                    rs.getLong(3),
                                    rs.getString(4).trim(),
                                    null));
                },
                tenantId,
                transactionId);
        return mirrored;
    }

    private void lockTransaction(UUID tenantId, UUID transactionId) {
        Integer locked =
                jdbc.query(
                        "SELECT 1 FROM ledger_transactions WHERE tenant_id = ? AND id = ? FOR UPDATE",
                        rs -> rs.next() ? 1 : 0,
                        tenantId,
                        transactionId);
        if (locked == null || locked == 0) {
            throw new LedgerException(ErrorCode.ACCOUNT_NOT_FOUND, "unknown transaction " + transactionId);
        }
    }

    private void lockBalance(UUID tenantId, UUID accountId) {
        jdbc.query(
                "SELECT 1 FROM balances WHERE tenant_id = ? AND account_id = ? FOR UPDATE",
                rs -> rs.next() ? 1 : 0,
                tenantId,
                accountId);
    }

    /** Carries the winning reversal's id, so the loser of a race can converge rather than retry. */
    public static class AlreadyReversedException extends LedgerException {
        private final UUID reversalId;

        public AlreadyReversedException(UUID reversalId) {
            super(ErrorCode.ALREADY_REVERSED, "already reversed by " + reversalId);
            this.reversalId = reversalId;
        }

        public UUID reversalId() {
            return reversalId;
        }
    }
}
