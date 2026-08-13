package org.elyonar.fincore.core.orchestration.internal.saga;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.elyonar.fincore.core.orchestration.api.CoreProperties;

/**
 * Teller tills.
 *
 * <p>A till is a ledger account, so cash movement is double-entry like everything else rather than
 * a special case. This table is the operational record of which account belongs to which till and
 * whether that till is open.
 *
 * <p>A deliberate v1 simplification: a till is really a Branch concern and there is no Branch domain
 * yet, so it lives in orchestration because the money path needs it. It moves when the teller
 * application arrives (design.md).
 */
@Repository
public class TillRecords {

    private final JdbcTemplate jdbc;

    public TillRecords(@Qualifier(CoreProperties.Beans.ORCHESTRATION_JDBC) JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private void scopeTo(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    /**
     * Provisions a till against an existing ledger account, inside a validated branch.
     *
     * <p>The unit id comes from the Organization module's port, never from the request — the
     * controller resolves the branch code and refuses a till whose branch does not exist
     * (ADR 0012). Kept as a plain column rather than a foreign key because the units table is
     * another module's schema.
     */
    @Transactional
    public UUID open(
            UUID tenantId,
            String branchCode,
            UUID unitId,
            UUID ledgerAccountId,
            String currency,
            String assignedTo) {
        scopeTo(tenantId);
        return jdbc.queryForObject(
                """
                INSERT INTO orchestration.tills
                    (tenant_id, branch_code, unit_id, ledger_account_id, currency, assigned_to)
                VALUES (?,?,?,?,?,?)
                RETURNING id
                """,
                UUID.class, tenantId, branchCode, unitId, ledgerAccountId, currency, assignedTo);
    }

    /**
     * Puts a till in somebody's hands, or takes it out of them.
     *
     * <p>Missing until now, and the omission was doing real harm: {@code assigned_to} could only be
     * set at the moment a till was opened. A till opened before anybody knew whose it would be
     * stayed unassigned for the life of the drawer, and the only remedy was to close it and open
     * another — a new till, a new id, and a break in the operational record of a drawer that never
     * physically moved.
     *
     * <p>Only an open till. Reassigning a closed one would rewrite who was answerable for cash that
     * has already been counted and signed off, which is the one thing the record exists to hold
     * still. Returns false when there is no such open till, so the caller answers 404 rather than
     * reporting success against nothing.
     *
     * <p>Null hands it back to nobody. That is a legitimate state — a teller leaves, the drawer
     * stays — and it differs from never having been assigned only in the audit trail, which is
     * where the difference belongs.
     */
    @Transactional
    public boolean assign(UUID tenantId, UUID tillId, String assignedTo) {
        scopeTo(tenantId);
        return jdbc.update(
                        "UPDATE orchestration.tills SET assigned_to = ? WHERE id = ? AND status = 'OPEN'",
                        assignedTo,
                        tillId)
                > 0;
    }

    /** The tenant's tills, open and closed — the operational inventory a supervisor reads. */
    @Transactional(readOnly = true)
    public java.util.List<TillSummary> list(UUID tenantId) {
        scopeTo(tenantId);
        return jdbc.query(
                "SELECT id, branch_code, unit_id, ledger_account_id, currency, assigned_to, status"
                        + " FROM orchestration.tills ORDER BY branch_code, opened_at",
                (rs, i) ->
                        new TillSummary(
                                rs.getObject("id", UUID.class),
                                rs.getString("branch_code"),
                                rs.getObject("unit_id", UUID.class),
                                rs.getObject("ledger_account_id", UUID.class),
                                rs.getString("currency"),
                                rs.getString("assigned_to"),
                                rs.getString("status")));
    }

    /**
     * The till, if it is open.
     *
     * <p>Openness is part of the query rather than a field the caller checks, so there is no
     * moment between reading and using it in which a closed till looks usable.
     */
    @Transactional(readOnly = true)
    public Till openTill(UUID tenantId, UUID tillId) {
        scopeTo(tenantId);
        return jdbc.query(
                "SELECT id, ledger_account_id, currency FROM orchestration.tills"
                        + " WHERE id = ? AND status = 'OPEN'",
                rs ->
                        rs.next()
                                ? new Till(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("ledger_account_id", UUID.class),
                                        rs.getString("currency"))
                                : null,
                tillId);
    }

    /** The till's ledger account, open or closed — a closed till's day is still readable. */
    @Transactional(readOnly = true)
    public UUID ledgerAccountOf(UUID tenantId, UUID tillId) {
        scopeTo(tenantId);
        return jdbc.query(
                "SELECT ledger_account_id FROM orchestration.tills WHERE id = ?",
                rs -> rs.next() ? rs.getObject("ledger_account_id", UUID.class) : null,
                tillId);
    }

    /**
     * The till's day (ui-runway.md §3): every saga that touched its account on the date. Runs in
     * a read transaction so the tenant scope is genuinely transaction-local — set_config outside
     * a transaction is a no-op, which RLS would answer with silence, not an error.
     */
    @Transactional(readOnly = true)
    /**
     * @param businessZone the institution's own timezone, which decides where its day begins.
     *     Comparing a {@code timestamptz} against a bare {@code ?::date} resolved the boundary in
     *     the *database session's* timezone — which pgjdbc sets from whichever JVM happens to be
     *     connected. A branch in Lagos on UTC servers would have its day cut at 01:00 local, and
     *     an hour of cash would appear on the wrong day's till while its daily limits, which have
     *     always used this zone, disagreed.
     */
    public java.util.List<java.util.Map<String, Object>> dayActivity(
            UUID tenantId, UUID accountId, String date, String businessZone) {
        scopeTo(tenantId);
        return jdbc.query(
                """
                SELECT id, reference, type, state, amount_minor, from_account_id, created_at
                  FROM orchestration.sagas
                 WHERE (from_account_id = ? OR to_account_id = ?)
                   AND created_at >= (?::date)::timestamp AT TIME ZONE ?
                   AND created_at <  ((?::date) + 1)::timestamp AT TIME ZONE ?
                 ORDER BY created_at
                """,
                (rs, i) -> {
                    var row = new java.util.LinkedHashMap<String, Object>();
                    row.put("sagaId", rs.getObject("id", UUID.class).toString());
                    // What the teller can read out. The id stays for the drawer to look up with.
                    row.put("reference", rs.getString("reference"));
                    row.put("type", rs.getString("type"));
                    row.put("state", rs.getString("state"));
                    row.put("amountMinor", Long.toString(rs.getLong("amount_minor")));
                    boolean out = accountId.equals(rs.getObject("from_account_id", UUID.class));
                    row.put("direction", out ? "OUT" : "IN");
                    row.put("at", rs.getObject("created_at", java.time.OffsetDateTime.class).toString());
                    return row;
                },
                accountId, accountId, date, businessZone, date, businessZone);
    }

    /** Closes a till. Cash cannot move through it afterwards. */
    @Transactional
    public void close(UUID tenantId, UUID tillId) {
        scopeTo(tenantId);
        jdbc.update(
                "UPDATE orchestration.tills SET status = 'CLOSED', closed_at = now() WHERE id = ?", tillId);
    }

    /** An open till and the ledger account it draws on. */
    public record Till(UUID id, UUID ledgerAccountId, String currency) {}

    /** A till as the admin surface lists it. */
    public record TillSummary(
            UUID id,
            String branchCode,
            UUID unitId,
            UUID ledgerAccountId,
            String currency,
            String assignedTo,
            String status) {}
}
