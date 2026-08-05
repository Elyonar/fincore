package org.elyonar.fincore.ledger.invariant;

import java.util.Optional;
import java.util.UUID;
import org.elyonar.fincore.ledger.tenant.TenantScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Answers "which entry ids are certainly settled?" — the one primitive three guarantees rest on.
 *
 * <p>Sequence values are handed out at insert, not at commit. A long-running posting can take
 * id=100 and commit it seconds after id=105 has already become visible. Any component that treats
 * "the highest id I can see" as "everything up to here has happened" is therefore wrong, and wrong
 * silently: the missed entry never errors, it simply never appears.
 *
 * <p>The horizon is read from PostgreSQL rather than inferred:
 * {@code pg_snapshot_xmin(pg_current_snapshot())} is the oldest transaction id still in flight.
 * An entry whose inserting transaction is strictly below that value has certainly committed or
 * aborted — no third outcome remains — so ids at or above it are excluded and left for the next
 * capture. The horizon therefore lags live traffic and never overtakes it, which is the only safe
 * direction for this error to point.
 *
 * <p>Deliberately its own class with its own test. The design calls for this to be proven
 * standalone before anchors, the relay, or anything else depends on it, because a subtly wrong
 * horizon would make every dependent check pass while verifying nothing.
 */
@Component
public class QuiesceHorizon {

    private final JdbcTemplate jdbc;
    private final TenantScope tenantScope;

    public QuiesceHorizon(JdbcTemplate jdbc, TenantScope tenantScope) {
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
    }

    /**
     * The highest entry id that is certainly settled, or empty when nothing qualifies yet.
     *
     * <p>{@code xmin} of the current snapshot is the boundary: every transaction below it has
     * finished. Entries are compared on their own {@code xmin}, which is the transaction that
     * inserted them.
     */
    public Optional<Long> settledEntryIdBound(UUID tenantId) {
        // Tenant-scoped, because anchors are per account and therefore per tenant. Reading
        // entries outside a tenant scope would return nothing under RLS — and nothing looks
        // exactly like "no settled entries", which is the sort of empty answer that quietly
        // disables a check rather than failing it.
        Long bound =
                tenantScope.inTenant(
                        tenantId,
                        () ->
                                jdbc.queryForObject(
                        """
                        SELECT MAX(id) FROM entries
                         WHERE tenant_id = ?
                           AND xmin::text::bigint < pg_snapshot_xmin(pg_current_snapshot())::text::bigint
                        """,
                                        Long.class,
                                        tenantId));
        return Optional.ofNullable(bound);
    }

    /** The oldest in-flight transaction id. Exposed so tests can assert the horizon actually moves. */
    public long oldestInFlightTransactionId() {
        Long xmin =
                jdbc.queryForObject(
                        "SELECT pg_snapshot_xmin(pg_current_snapshot())::text::bigint", Long.class);
        return xmin == null ? 0L : xmin;
    }
}
