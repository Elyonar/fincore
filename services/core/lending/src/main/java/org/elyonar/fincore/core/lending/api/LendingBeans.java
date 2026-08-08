package org.elyonar.fincore.core.lending.api;

/**
 * The Spring bean names this module's persistence is wired to — declared in the module that
 * consumes them, like {@code CustomerBeans} and the rest (ADR 0006).
 */
public final class LendingBeans {

    private LendingBeans() {}

    public static final String DATA_SOURCE = "lendingDataSource";
    public static final String JDBC = "lendingJdbcTemplate";
    public static final String TRANSACTION_MANAGER = "lendingTransactionManager";
}
