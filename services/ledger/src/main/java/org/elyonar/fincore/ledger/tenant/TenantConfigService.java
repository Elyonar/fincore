package org.elyonar.fincore.ledger.tenant;

import java.time.Duration;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Reads the currently effective configuration for a tenant. */
@Service
public class TenantConfigService {

    private final JdbcTemplate jdbc;

    public TenantConfigService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The highest version whose {@code effective_from} has arrived.
     *
     * <p>{@code version} orders the history and breaks ties; {@code effective_from} decides which
     * row is live. Both are needed: versions alone cannot express a change scheduled ahead of
     * time, and timestamps alone cannot order two rows that share one.
     */
    public TenantConfig currentFor(UUID tenantId) {
        TenantConfig config =
                jdbc.query(
                        """
                        SELECT business_timezone,
                               backdate_window_days,
                               EXTRACT(EPOCH FROM max_hold_ttl)::bigint AS max_hold_ttl_seconds,
                               max_entries_per_tx
                          FROM tenant_config
                         WHERE tenant_id = ? AND effective_from <= now()
                         ORDER BY version DESC
                         LIMIT 1
                        """,
                        rs ->
                                rs.next()
                                        ? new TenantConfig(
                                                ZoneId.of(rs.getString(1)),
                                                rs.getInt(2),
                                                Duration.ofSeconds(rs.getLong(3)),
                                                rs.getInt(4))
                                        : null,
                        tenantId);
        return config != null ? config : TenantConfig.PLATFORM_DEFAULTS;
    }

    // The interval is converted to seconds in SQL rather than mapped through the driver's own
    // PGInterval: the PostgreSQL driver is a runtime dependency, and compiling against its
    // internals would make the choice of driver a compile-time coupling.
}
