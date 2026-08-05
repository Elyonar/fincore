package org.elyonar.fincore.ledger.posting;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.ledger.shared.ErrorCode;
import org.elyonar.fincore.ledger.shared.LedgerException;
import org.elyonar.fincore.ledger.tenant.TenantScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Reads a transaction back with its entries.
 *
 * <p>How a caller confirms what actually posted — for reconciliation, for showing a teller the
 * result of an operation, and for resolving a dispute against the record rather than against
 * someone's memory of it. A transaction is bounded (at most a hundred entries), so this needs no
 * paging of its own.
 */
@Service
public class TransactionReadService {

    private final TenantScope tenantScope;
    private final JdbcTemplate jdbc;

    public TransactionReadService(TenantScope tenantScope, JdbcTemplate jdbc) {
        this.tenantScope = tenantScope;
        this.jdbc = jdbc;
    }

    public TransactionView find(UUID tenantId, UUID transactionId) {
        TransactionView view = tenantScope.inTenant(tenantId, () -> read(tenantId, transactionId));
        if (view == null) {
            // Another tenant's transaction is indistinguishable from one that does not exist.
            throw new LedgerException(ErrorCode.ACCOUNT_NOT_FOUND, "unknown transaction " + transactionId);
        }
        return view;
    }

    private TransactionView read(UUID tenantId, UUID transactionId) {
        var header =
                jdbc.query(
                        """
                        SELECT id, idempotency_key, status, initiated_by, executed_by,
                               reverses_transaction_id, relates_to_transaction_id, backdate_reason, posted_at
                          FROM ledger_transactions WHERE tenant_id = ? AND id = ?
                        """,
                        rs ->
                                rs.next()
                                        ? new Object[] {
                                            rs.getObject(1, UUID.class),
                                            rs.getString(2),
                                            rs.getString(3),
                                            rs.getString(4),
                                            rs.getString(5),
                                            rs.getObject(6, UUID.class),
                                            rs.getObject(7, UUID.class),
                                            rs.getString(8),
                                            rs.getTimestamp(9).toInstant()
                                        }
                                        : null,
                        tenantId,
                        transactionId);
        if (header == null) {
            return null;
        }

        List<EntryView> entries =
                jdbc.query(
                        """
                        SELECT id, account_id, direction, amount_minor, currency, value_date, booked_at
                          FROM entries WHERE tenant_id = ? AND transaction_id = ? ORDER BY id
                        """,
                        (rs, i) ->
                                new EntryView(
                                        rs.getLong(1),
                                        rs.getObject(2, UUID.class),
                                        rs.getString(3),
                                        rs.getLong(4),
                                        rs.getString(5).trim(),
                                        rs.getObject(6, LocalDate.class),
                                        rs.getTimestamp(7).toInstant()),
                        tenantId,
                        transactionId);

        return new TransactionView(
                (UUID) header[0],
                (String) header[1],
                (String) header[2],
                (String) header[3],
                (String) header[4],
                (UUID) header[5],
                (UUID) header[6],
                (String) header[7],
                (Instant) header[8],
                entries);
    }

    public record TransactionView(
            UUID id,
            String idempotencyKey,
            String status,
            String initiatedBy,
            String executedBy,
            UUID reversesTransactionId,
            UUID relatesToTransactionId,
            String backdateReason,
            Instant postedAt,
            List<EntryView> entries) {}

    public record EntryView(
            long entryId,
            UUID accountId,
            String direction,
            long amountMinor,
            String currency,
            LocalDate valueDate,
            Instant bookedAt) {}
}
