package org.elyonar.fincore.core.product.api;

/**
 * The Spring bean names this module's persistence is wired to.
 *
 * <p>Declared in the module that consumes them: Product may not import from Orchestration or
 * Customer, so a shared constants class would create exactly the cross-module dependency the
 * boundary forbids (ADR 0006). See {@code CustomerBeans} for the same reasoning.
 */
public final class ProductBeans {

    private ProductBeans() {}

    public static final String DATA_SOURCE = "productDataSource";
    public static final String JDBC = "productJdbcTemplate";
    public static final String TRANSACTION_MANAGER = "productTransactionManager";
}
