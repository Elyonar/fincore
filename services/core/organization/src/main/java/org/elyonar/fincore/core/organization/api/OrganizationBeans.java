package org.elyonar.fincore.core.organization.api;

/**
 * The Spring bean names this module's persistence is wired to.
 *
 * <p>Declared here, in the module that consumes them, for the same reason {@code CustomerBeans}
 * and {@code ProductBeans} are: modules integrate through published interfaces, and a shared
 * constants class would be a compile-time dependency between modules that are meant to have none
 * (ADR 0006).
 */
public final class OrganizationBeans {

    private OrganizationBeans() {}

    public static final String DATA_SOURCE = "organizationDataSource";
    public static final String JDBC = "organizationJdbcTemplate";
    public static final String TRANSACTION_MANAGER = "organizationTransactionManager";
}
