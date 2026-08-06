package org.elyonar.fincore.ledger.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * A single, pinned database connection carrying one tenant's RLS context.
 *
 * <p>Tests that exercise SQL-level rules must own their connection. The tenant context lives in a
 * PostgreSQL session variable, and a pooled {@code JdbcTemplate} may serve consecutive statements
 * from different physical connections — so a {@code SET} and the query that depends on it can
 * land in different sessions. That is precisely the defect {@code SET LOCAL} exists to prevent,
 * and a test suite that quietly depended on getting the same connection twice would be asserting
 * luck.
 *
 * <p>Autocommit stays on: each statement is its own transaction, so a test that expects a
 * constraint violation does not poison the statements after it. The context is therefore set at
 * session scope on this connection alone, and dies with it.
 */
public final class TenantSession implements AutoCloseable {

    private final Connection connection;

    private TenantSession(Connection connection) {
        this.connection = connection;
    }

    /**
     * Opens a connection and pins it to {@code tenant}, registering the tenant first.
     *
     * <p>Registration stands in for the provisioning script: a tenant must exist before it can hold
     * money, and tests are not exempt from that. {@code TenantRegistryTest} covers the case this
     * deliberately does not — an id that was never provisioned.
     */
    public static TenantSession open(DataSource dataSource, UUID tenant) {
        try {
            Connection c = dataSource.getConnection();
            c.setAutoCommit(true);
            if (tenant != null) {
                try (PreparedStatement register =
                        c.prepareStatement(
                                "INSERT INTO tenants (id, name, created_by) VALUES (?, 'test tenant', 'test')"
                                        + " ON CONFLICT (id) DO NOTHING")) {
                    register.setObject(1, tenant);
                    register.execute();
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
                ps.setString(1, tenant == null ? "" : tenant.toString());
                ps.execute();
            }
            return new TenantSession(c);
        } catch (SQLException e) {
            throw new IllegalStateException("could not open a tenant-scoped session", e);
        }
    }

    /** Opens a connection with <em>no</em> tenant context — RLS should reveal nothing through it. */
    public static TenantSession openWithoutTenant(DataSource dataSource) {
        return open(dataSource, null);
    }

    public void execute(String sql, Object... args) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, args);
            ps.execute();
        } catch (SQLException e) {
            throw new SqlFailure(e.getMessage(), e);
        }
    }

    public long count(String sql, Object... args) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new SqlFailure(e.getMessage(), e);
        }
    }

    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Returning the connection to the pool is best-effort in a test.
        }
    }

    /** Unchecked wrapper so tests can assert on failure without checked-exception noise. */
    public static final class SqlFailure extends RuntimeException {
        public SqlFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
