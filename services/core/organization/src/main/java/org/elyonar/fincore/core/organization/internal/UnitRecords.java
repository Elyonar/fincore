package org.elyonar.fincore.core.organization.internal;

import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.core.organization.api.OrganizationBeans;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The organizational tree and its assignments — the module's writes and admin reads.
 *
 * <p>Plain SQL over the module's own role, tenant-scoped on every connection. Races over unit
 * codes and live assignments are arbitrated by unique indexes, not by application checks — the
 * same discipline the sagas apply to idempotency keys.
 */
@Repository
public class UnitRecords {

    private final JdbcTemplate jdbc;

    public UnitRecords(@Qualifier(OrganizationBeans.JDBC) JdbcTemplate organizationJdbcTemplate) {
        this.jdbc = organizationJdbcTemplate;
    }

    private void scopeTo(UUID tenantId) {
        // SET LOCAL, never a session SET: connections are pooled across tenants (ADR 0007).
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    /** Creates a unit, optionally under a parent named by code. */
    @Transactional(transactionManager = OrganizationBeans.TRANSACTION_MANAGER)
    public Unit create(
            UUID tenantId, String code, String name, String unitType, String parentCode, String createdBy) {
        scopeTo(tenantId);

        UUID parentId = null;
        if (parentCode != null && !parentCode.isBlank()) {
            parentId =
                    jdbc.query(
                            "SELECT id FROM organization.organizational_units"
                                    + " WHERE code = ? AND status = 'ACTIVE'",
                            rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                            parentCode);
            if (parentId == null) {
                throw new NoSuchParent();
            }
        }

        try {
            return jdbc.queryForObject(
                    """
                    INSERT INTO organization.organizational_units
                        (tenant_id, parent_unit_id, unit_type, code, name, created_by)
                    VALUES (?,?,?,?,?,?)
                    RETURNING id, code, name, unit_type, parent_unit_id, status
                    """,
                    (rs, i) -> unit(rs.getObject("id", UUID.class), rs),
                    tenantId,
                    parentId,
                    unitType,
                    code,
                    name,
                    createdBy);
        } catch (DuplicateKeyException e) {
            // The unique index arbitrates, including closed units: codes never recycle, because a
            // reused code would make an old till's branch ambiguous in an audit.
            throw new CodeTaken();
        }
    }

    /** The unit, or null — absent, another tenant's, both indistinguishable. */
    @Transactional(readOnly = true, transactionManager = OrganizationBeans.TRANSACTION_MANAGER)
    public Unit read(UUID tenantId, UUID id) {
        scopeTo(tenantId);
        return jdbc.query(
                "SELECT id, code, name, unit_type, parent_unit_id, status"
                        + " FROM organization.organizational_units WHERE id = ?",
                rs -> rs.next() ? unit(rs.getObject("id", UUID.class), rs) : null,
                id);
    }

    /** Every unit, tree order left to the caller. Tenants are small; paging arrives with need. */
    @Transactional(readOnly = true, transactionManager = OrganizationBeans.TRANSACTION_MANAGER)
    public List<Unit> list(UUID tenantId) {
        scopeTo(tenantId);
        return jdbc.query(
                "SELECT id, code, name, unit_type, parent_unit_id, status"
                        + " FROM organization.organizational_units ORDER BY code",
                (rs, i) -> unit(rs.getObject("id", UUID.class), rs));
    }

    /** Closes a unit. Its history — assignments, tills that named it — stays attributed to it. */
    @Transactional(transactionManager = OrganizationBeans.TRANSACTION_MANAGER)
    public void close(UUID tenantId, UUID id) {
        scopeTo(tenantId);
        int updated =
                jdbc.update(
                        "UPDATE organization.organizational_units"
                                + " SET status = 'CLOSED', closed_at = now()"
                                + " WHERE id = ? AND status = 'ACTIVE'",
                        id);
        if (updated == 0) {
            throw new NoSuchUnit();
        }
    }

    /** Assigns a principal to a unit. The live-assignment index arbitrates duplicates. */
    @Transactional(transactionManager = OrganizationBeans.TRANSACTION_MANAGER)
    public UUID assign(UUID tenantId, UUID unitId, String principal, String assignedBy) {
        scopeTo(tenantId);
        Integer live =
                jdbc.query(
                        "SELECT 1 FROM organization.organizational_units WHERE id = ? AND status = 'ACTIVE'",
                        rs -> rs.next() ? 1 : null,
                        unitId);
        if (live == null) {
            throw new NoSuchUnit();
        }
        try {
            return jdbc.queryForObject(
                    """
                    INSERT INTO organization.unit_assignments (tenant_id, unit_id, principal, assigned_by)
                    VALUES (?,?,?,?)
                    RETURNING id
                    """,
                    UUID.class,
                    tenantId,
                    unitId,
                    principal,
                    assignedBy);
        } catch (DuplicateKeyException e) {
            throw new AlreadyAssigned();
        }
    }

    /** Revokes a live assignment, attributed. History is kept, never deleted. */
    @Transactional(transactionManager = OrganizationBeans.TRANSACTION_MANAGER)
    public void revoke(UUID tenantId, UUID unitId, String principal, String revokedBy) {
        scopeTo(tenantId);
        int updated =
                jdbc.update(
                        """
                        UPDATE organization.unit_assignments
                           SET revoked_at = now(), revoked_by = ?
                         WHERE unit_id = ? AND principal = ? AND revoked_at IS NULL
                        """,
                        revokedBy,
                        unitId,
                        principal);
        if (updated == 0) {
            throw new NoSuchAssignment();
        }
    }

    /** The live principals of a unit — what identity provisioning reads (ADR 0012). */
    @Transactional(readOnly = true, transactionManager = OrganizationBeans.TRANSACTION_MANAGER)
    public List<Assignment> assignments(UUID tenantId, UUID unitId) {
        scopeTo(tenantId);
        return jdbc.query(
                """
                SELECT id, principal, assigned_by, assigned_at
                  FROM organization.unit_assignments
                 WHERE unit_id = ? AND revoked_at IS NULL
                 ORDER BY assigned_at
                """,
                (rs, i) ->
                        new Assignment(
                                rs.getObject("id", UUID.class),
                                rs.getString("principal"),
                                rs.getString("assigned_by"),
                                rs.getObject("assigned_at", java.time.OffsetDateTime.class)),
                unitId);
    }

    private static Unit unit(UUID id, java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Unit(
                id,
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("unit_type"),
                rs.getObject("parent_unit_id", UUID.class),
                rs.getString("status"));
    }

    /** A unit as the admin surface returns it. */
    public record Unit(UUID id, String code, String name, String unitType, UUID parentUnitId, String status) {}

    /** A live assignment. */
    public record Assignment(UUID id, String principal, String assignedBy, java.time.OffsetDateTime assignedAt) {}

    public static class NoSuchUnit extends RuntimeException {}

    public static class NoSuchParent extends RuntimeException {}

    public static class CodeTaken extends RuntimeException {}

    public static class AlreadyAssigned extends RuntimeException {}

    public static class NoSuchAssignment extends RuntimeException {}
}
