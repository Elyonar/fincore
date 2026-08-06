package org.elyonar.fincore.core.orchestration.internal.saga;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

    public TillRecords(@Qualifier("orchestrationJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private void scopeTo(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    /** Provisions a till against an existing ledger account. */
    @Transactional
    public UUID open(UUID tenantId, String branchCode, UUID ledgerAccountId, String currency, String assignedTo) {
        scopeTo(tenantId);
        return jdbc.queryForObject(
                """
                INSERT INTO orchestration.tills
                    (tenant_id, branch_code, ledger_account_id, currency, assigned_to)
                VALUES (?,?,?,?,?)
                RETURNING id
                """,
                UUID.class, tenantId, branchCode, ledgerAccountId, currency, assignedTo);
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

    /** Closes a till. Cash cannot move through it afterwards. */
    @Transactional
    public void close(UUID tenantId, UUID tillId) {
        scopeTo(tenantId);
        jdbc.update(
                "UPDATE orchestration.tills SET status = 'CLOSED', closed_at = now() WHERE id = ?", tillId);
    }

    /** An open till and the ledger account it draws on. */
    public record Till(UUID id, UUID ledgerAccountId, String currency) {}
}
