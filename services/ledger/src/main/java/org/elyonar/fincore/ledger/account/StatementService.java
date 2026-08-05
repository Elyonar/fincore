package org.elyonar.fincore.ledger.account;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.ledger.period.PeriodService;
import org.elyonar.fincore.ledger.shared.ErrorCode;
import org.elyonar.fincore.ledger.shared.LedgerException;
import org.elyonar.fincore.ledger.tenant.TenantScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Statements: a bounded period document, not a cursor over live data.
 *
 * <p>Modelled on how the industry actually issues them (ISO 20022 {@code camt.053}, SWIFT MT940).
 * Three properties come from that, and each earns its place:
 *
 * <ul>
 *   <li><strong>It reconciles.</strong> Opening + movements = closing. That equality is the
 *       statement's own proof of integrity — the same thing {@code :60F:}/{@code :62F:} carry in
 *       MT940 and {@code OPBD}/{@code CLBD} in camt.053 — and it is checked before the document is
 *       returned rather than trusted.
 *   <li><strong>Every line carries both dates.</strong> {@code valueDate} is when it counts,
 *       {@code bookedAt} is when the ledger recorded it. A backdated item sorts into business
 *       order and still reads correctly, because its booking date sits beside it.
 *   <li><strong>Closed periods are final; the open period is interim.</strong> No posting can land
 *       in a closed period, so that statement is immutable by construction and re-requesting it
 *       returns the same document forever — with no snapshot, no pinned cursor, no as-of
 *       machinery. The standards draw exactly this line themselves: camt.053 versus camt.052.
 * </ul>
 *
 * <p>This endpoint is deliberately unusable as a change feed. Entry ids are assigned at insert
 * rather than commit, so a durable cursor over them silently skips late-committing entries; the
 * outbox is the change feed.
 */
@Service
public class StatementService {

    private final TenantScope tenantScope;
    private final JdbcTemplate jdbc;
    private final PeriodService periods;

    public StatementService(TenantScope tenantScope, JdbcTemplate jdbc, PeriodService periods) {
        this.tenantScope = tenantScope;
        this.jdbc = jdbc;
        this.periods = periods;
    }

    /** Default lines per page. A whole year of a busy account is not a response to build in memory. */
    public static final int DEFAULT_PAGE_SIZE = 500;

    public static final int MAX_PAGE_SIZE = 1000;

    public Statement forPeriod(UUID tenantId, UUID accountId, LocalDate from, LocalDate to) {
        return forPeriod(tenantId, accountId, from, to, DEFAULT_PAGE_SIZE, null);
    }

    /**
     * One page of a statement.
     *
     * <p>{@code after} walks the lines of this one bounded period and is spent when the walk ends.
     * It is emphatically not a change-feed cursor: those are durable across time, and holding one
     * over entry ids silently skips late-committing entries. The outbox is the change feed.
     */
    public Statement forPeriod(
            UUID tenantId, UUID accountId, LocalDate from, LocalDate to, int pageSize, String after) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new LedgerException(ErrorCode.VALUE_DATE_INVALID, "a statement needs a period, and from <= to");
        }
        int size = Math.min(pageSize <= 0 ? DEFAULT_PAGE_SIZE : pageSize, MAX_PAGE_SIZE);
        Cursor cursor = Cursor.parse(after);
        return tenantScope.inTenant(tenantId, () -> build(tenantId, accountId, from, to, size, cursor));
    }

    private Statement build(
            UUID tenantId, UUID accountId, LocalDate from, LocalDate to, int pageSize, Cursor after) {
        String currency =
                jdbc.query(
                        "SELECT currency FROM accounts WHERE tenant_id = ? AND id = ?",
                        rs -> rs.next() ? rs.getString(1).trim() : null,
                        tenantId,
                        accountId);
        if (currency == null) {
            throw new LedgerException(ErrorCode.ACCOUNT_NOT_FOUND, "unknown account " + accountId);
        }

        long opening = movementBefore(tenantId, accountId, from);

        // Keyset on the full sort key. Ordering is (value_date, id), so a cursor of id alone
        // would skip or repeat lines wherever several entries share a value date.
        List<Line> lines =
                jdbc.query(
                        """
                        SELECT id, transaction_id, direction, amount_minor, currency, value_date, booked_at
                          FROM entries
                         WHERE tenant_id = ? AND account_id = ? AND value_date >= ? AND value_date <= ?
                           AND (?::date IS NULL OR (value_date, id) > (?::date, ?::bigint))
                         ORDER BY value_date, id
                         LIMIT ?
                        """,
                        (rs, i) ->
                                new Line(
                                        rs.getLong(1),
                                        rs.getObject(2, UUID.class),
                                        rs.getString(3),
                                        rs.getLong(4),
                                        rs.getString(5).trim(),
                                        rs.getObject(6, LocalDate.class),
                                        rs.getTimestamp(7).toInstant()),
                        tenantId,
                        accountId,
                        from,
                        to,
                        after == null ? null : after.valueDate(),
                        after == null ? null : after.valueDate(),
                        after == null ? null : after.entryId(),
                        pageSize + 1);

        // One row beyond the page tells us whether more remain, without a second count query.
        boolean hasMore = lines.size() > pageSize;
        if (hasMore) {
            lines = lines.subList(0, pageSize);
        }
        String nextCursor =
                hasMore && !lines.isEmpty()
                        ? new Cursor(lines.get(lines.size() - 1).valueDate(), lines.get(lines.size() - 1).entryId())
                                .encode()
                        : null;

        // opening and closing describe the whole period on every page: they are the document's
        // header, not a running total. Summing this page would make each page look like its own
        // statement and none of them reconcile.
        long closing = movementBefore(tenantId, accountId, to.plusDays(1));

        // A statement that does not reconcile is not a statement. On a single page the check is
        // exact; across pages the caller verifies the same equality by summing every page, which is
        // the property the header exists to make checkable.
        if (after == null && !hasMore) {
            long movements = lines.stream().mapToLong(Line::signedMinor).sum();
            if (opening + movements != closing) {
                throw new IllegalStateException(
                        "statement does not reconcile for account " + accountId + " over " + from + ".." + to);
            }
        }

        // Final only if the whole period is closed: any part still open can still receive a
        // backdated posting.
        boolean isFinal = periods.isClosed(tenantId, to);

        return new Statement(accountId, currency, from, to, opening, closing, isFinal, lines, nextCursor);
    }

    /** Net movement strictly before {@code date}, which is the opening balance for a period. */
    private long movementBefore(UUID tenantId, UUID accountId, LocalDate date) {
        Long sum =
                jdbc.queryForObject(
                        """
                        SELECT COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount_minor
                                                 ELSE -amount_minor END), 0)
                          FROM entries
                         WHERE tenant_id = ? AND account_id = ? AND value_date < ?
                        """,
                        Long.class,
                        tenantId,
                        accountId,
                        date);
        return sum == null ? 0L : sum;
    }

    public record Statement(
            UUID accountId,
            String currency,
            LocalDate from,
            LocalDate to,
            long openingMinor,
            long closingMinor,
            boolean isFinal,
            List<Line> lines,
            /** Non-null while more lines remain in this period. Spent when the walk ends. */
            String nextCursor) {}

    /** A position in one period's ordering. Encodes the full sort key, not just the id. */
    private record Cursor(LocalDate valueDate, long entryId) {

        String encode() {
            return valueDate + ":" + entryId;
        }

        static Cursor parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            int split = raw.lastIndexOf(':');
            if (split < 0) {
                throw new LedgerException(ErrorCode.VALUE_DATE_INVALID, "malformed statement cursor");
            }
            try {
                return new Cursor(LocalDate.parse(raw.substring(0, split)), Long.parseLong(raw.substring(split + 1)));
            } catch (RuntimeException e) {
                throw new LedgerException(ErrorCode.VALUE_DATE_INVALID, "malformed statement cursor");
            }
        }
    }

    public record Line(
            long entryId,
            UUID transactionId,
            String direction,
            long amountMinor,
            String currency,
            LocalDate valueDate,
            Instant bookedAt) {

        long signedMinor() {
            return "CREDIT".equals(direction) ? amountMinor : -amountMinor;
        }
    }
}
