package org.elyonar.fincore.core.organization.internal;

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
 * <p>Read-only, deliberately — consulted inside the caller's own flow and writing nothing there,
 * the same shape as {@code JdbcCustomerEligibility}. Each query sets the tenant context on its own
 * connection, because row-level security is a backstop and a backstop that is only active
 * sometimes is not one (ADR 0007).
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
}
