package org.elyonar.fincore.core.orchestration.internal;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.UUID;
import org.elyonar.fincore.core.orchestration.api.CoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The tenant's business timezone — the authority for "which calendar day is it".
 *
 * <p>Read from {@code platform.tenants}, which is a fact about the deployable rather than any
 * module (its V1 deliberately granted module roles SELECT for exactly this kind of question, the
 * same way {@code TenantGate} asks "is this tenant real" on every request). It matters because
 * the DAILY limit window rolls at the tenant's midnight: a hardcoded zone gave every tenant
 * Lagos midnight, which is the wrong regulatory day everywhere else.
 *
 * <p>An unparseable zone falls back to the platform default and says so at WARN — refusing money
 * movement over a bad configuration string would be an outage a typo can cause, while a wrong
 * window is bounded and visible.
 */
@Component
public class TenantZones {

    private static final Logger log = LoggerFactory.getLogger(TenantZones.class);

    /** Constitution 11: build for Nigeria first. The default a tenant gets until provisioning says otherwise. */
    static final ZoneId DEFAULT = ZoneId.of("Africa/Lagos");

    private final JdbcTemplate jdbc;

    public TenantZones(@Qualifier(CoreProperties.Beans.ORCHESTRATION_JDBC) JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The zone the tenant's business day rolls in. Never null. */
    public ZoneId businessZone(UUID tenantId) {
        String zone =
                jdbc.query(
                        "SELECT business_timezone FROM platform.tenants WHERE id = ?",
                        rs -> rs.next() ? rs.getString(1) : null,
                        tenantId);
        if (zone == null) {
            // Unregistered tenants never get here — TenantGate refused them — so this is only
            // the window between registration models. The default is the safe answer.
            return DEFAULT;
        }
        try {
            return ZoneId.of(zone);
        } catch (DateTimeException e) {
            log.warn("tenant {} carries unparseable business_timezone '{}'; using {}", tenantId, zone, DEFAULT);
            return DEFAULT;
        }
    }
}
