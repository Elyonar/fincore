package org.elyonar.fincore.core.organization.internal;

import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.core.organization.api.OrganizationBeans;
import org.elyonar.fincore.core.organization.api.OrganizationUnits;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Organization's answer to the one question a neighbour may ask.
 *
 * <p>Reads are consulted inside the caller's own flow and write nothing, the same shape as
 * {@code JdbcCustomerEligibility}. The one write is assignment replacement, which exists because
 * ADR 0017 requires the Core record and the {@code units} claim to move in one operation. Each
 * query sets the tenant context on its own connection, because row-level security is a backstop
 * and a backstop that is only active sometimes is not one (ADR 0007).
 */
@Service
public class JdbcOrganizationUnits implements OrganizationUnits {

    private final JdbcTemplate jdbc;

    public JdbcOrganizationUnits(@Qualifier(OrganizationBeans.JDBC) JdbcTemplate organizationJdbcTemplate) {
        this.jdbc = organizationJdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true, transactionManager = OrganizationBeans.TRANSACTION_MANAGER)
    public Unit activeUnitByCode(UUID tenantId, String code) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
        return jdbc.query(
                "SELECT id, code, unit_type FROM organization.organizational_units"
                        + " WHERE code = ? AND status = 'ACTIVE'",
                rs ->
                        rs.next()
                                ? new Unit(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("code"),
                                        rs.getString("unit_type"))
                                : null,
                code);
    }

    @Override
    @Transactional(transactionManager = OrganizationBeans.TRANSACTION_MANAGER)
    public List<String> replaceAssignments(
            UUID tenantId, String principal, List<String> unitCodes, String changedBy) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());

        List<String> wanted = unitCodes == null
                ? List.of()
                : unitCodes.stream().filter(c -> c != null && !c.isBlank()).map(String::trim).distinct().toList();

        // Resolve every code first, so a request naming one bad unit changes nothing at all. A
        // partially applied assignment is the worst outcome here: the administrator sees an error
        // and the teller's scope has silently moved anyway.
        for (String code : wanted) {
            Unit unit = jdbc.query(
                    "SELECT id, code, unit_type FROM organization.organizational_units"
                            + " WHERE code = ? AND status = 'ACTIVE'",
                    rs -> rs.next()
                            ? new Unit(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("unit_type"))
                            : null,
                    code);
            if (unit == null) {
                throw new NoSuchUnit(code);
            }
        }

        // Revoked, not deleted: the assignment table is history as much as state, and an audit
        // asking where somebody worked last quarter is answered by rows that were not erased.
        jdbc.update(
                "UPDATE organization.unit_assignments SET revoked_at = now(), revoked_by = ?"
                        + " WHERE tenant_id = ? AND principal = ? AND revoked_at IS NULL"
                        + " AND unit_id NOT IN (SELECT id FROM organization.organizational_units"
                        + "   WHERE tenant_id = ? AND code = ANY (?))",
                changedBy,
                tenantId,
                principal,
                tenantId,
                wanted.toArray(String[]::new));

        for (String code : wanted) {
            jdbc.update(
                    "INSERT INTO organization.unit_assignments (tenant_id, unit_id, principal, assigned_by)"
                            + " SELECT ?, u.id, ?, ? FROM organization.organizational_units u"
                            + " WHERE u.tenant_id = ? AND u.code = ? AND u.status = 'ACTIVE'"
                            + " AND NOT EXISTS (SELECT 1 FROM organization.unit_assignments a"
                            + "   WHERE a.tenant_id = u.tenant_id AND a.unit_id = u.id"
                            + "   AND a.principal = ? AND a.revoked_at IS NULL)",
                    tenantId,
                    principal,
                    changedBy,
                    tenantId,
                    code,
                    principal);
        }
        return wanted;
    }

    @Override
    @Transactional(readOnly = true, transactionManager = OrganizationBeans.TRANSACTION_MANAGER)
    public List<String> assignmentsOf(UUID tenantId, String principal) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
        return jdbc.queryForList(
                "SELECT u.code FROM organization.unit_assignments a"
                        + " JOIN organization.organizational_units u ON u.tenant_id = a.tenant_id AND u.id = a.unit_id"
                        + " WHERE a.tenant_id = ? AND a.principal = ? AND a.revoked_at IS NULL"
                        + " ORDER BY u.code",
                String.class,
                tenantId,
                principal);
    }
}
