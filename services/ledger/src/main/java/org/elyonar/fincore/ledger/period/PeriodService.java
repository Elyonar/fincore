package org.elyonar.fincore.ledger.period;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.ledger.shared.ErrorCode;
import org.elyonar.fincore.ledger.shared.LedgerException;
import org.elyonar.fincore.ledger.tenant.TenantScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Accounting periods: the mechanism that makes a signed-off statement stay signed off.
 *
 * <p>Once a period is closed, no posting may carry a value date inside it. That single rule is
 * what lets a statement over a closed period be reproducible forever — no snapshot, no pinned
 * cursor, no as-of machinery. There is no reopen: a period that could be reopened would guarantee
 * nothing.
 */
@Service
public class PeriodService {

    private final TenantScope tenantScope;
    private final JdbcTemplate jdbc;

    public PeriodService(TenantScope tenantScope, JdbcTemplate jdbc) {
        this.tenantScope = tenantScope;
        this.jdbc = jdbc;
    }

    /** Closes everything up to and including {@code periodEnd}. Attributed; maker-checker upstream. */
    public void close(UUID tenantId, LocalDate periodEnd, String closedBy) {
        tenantScope.inTenant(
                tenantId,
                () -> {
                    int inserted =
                            jdbc.update(
                                    "INSERT INTO accounting_periods (tenant_id, period_end, closed_by)"
                                            + " VALUES (?,?,?) ON CONFLICT DO NOTHING",
                                    tenantId,
                                    periodEnd,
                                    closedBy);
                    if (inserted == 0) {
                        throw new LedgerException(
                                ErrorCode.VALUE_DATE_INVALID, "period ending " + periodEnd + " is already closed");
                    }
                });
    }

    public List<ClosedPeriod> list(UUID tenantId) {
        return tenantScope.inTenant(
                tenantId,
                () ->
                        jdbc.query(
                                "SELECT period_end, closed_at, closed_by FROM accounting_periods"
                                        + " WHERE tenant_id = ? ORDER BY period_end DESC",
                                (rs, i) ->
                                        new ClosedPeriod(
                                                rs.getObject(1, LocalDate.class),
                                                rs.getTimestamp(2).toInstant(),
                                                rs.getString(3)),
                                tenantId));
    }

    /**
     * True when {@code valueDate} falls inside a closed period.
     *
     * <p>Periods close forward, so any close at or after the date covers it.
     */
    public boolean isClosed(UUID tenantId, LocalDate valueDate) {
        Long covering =
                jdbc.queryForObject(
                        "SELECT count(*) FROM accounting_periods WHERE tenant_id = ? AND period_end >= ?",
                        Long.class,
                        tenantId,
                        valueDate);
        return covering != null && covering > 0;
    }

    public record ClosedPeriod(LocalDate periodEnd, java.time.Instant closedAt, String closedBy) {}
}
