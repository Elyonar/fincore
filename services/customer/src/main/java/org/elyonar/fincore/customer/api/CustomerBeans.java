package org.elyonar.fincore.customer.api;

/**
 * The Spring bean names this module's persistence is wired to.
 *
 * <p>Declared here, in the module that consumes them, rather than in one platform-wide constants
 * class. Customer may not import from Orchestration and vice versa — modules integrate through
 * published interfaces, and the boundary is enforced by per-module database roles (ADR 0006). A
 * shared constants class would be a compile-time dependency between two modules that are meant to
 * have none, so "one place" here means one place *per module*.
 *
 * <p>They are named at all because {@code @Qualifier} takes a string: a typo is a startup failure
 * at best, and at worst binds this module's work to another module's role, routing around the
 * GRANT that is supposed to contain it.
 */
public final class CustomerBeans {

    private CustomerBeans() {}

    public static final String DATA_SOURCE = "customerDataSource";
    public static final String JDBC = "customerJdbcTemplate";
    public static final String TRANSACTION_MANAGER = "customerTransactionManager";
}
