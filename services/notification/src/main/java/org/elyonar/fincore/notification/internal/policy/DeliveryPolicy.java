package org.elyonar.fincore.notification.internal.policy;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a tenant has configured: which channels a category uses, in what order, and when it may not
 * send.
 *
 * <p>The one rule worth stating twice is the quiet-hours exemption. **A transactional alert is a
 * fraud control**, and a debit alert held until 07:00 about a 02:00 debit has been converted from a
 * control into a receipt — the customer learns about the theft five hours after the only moment
 * they could have stopped it. So quiet hours apply to service and marketing messages and never to
 * transactional ones, and that is policy rather than configuration: a tenant cannot switch it off.
 */
@Component
public class DeliveryPolicy {

    public static final String TRANSACTIONAL = "TRANSACTIONAL";

    private final JdbcTemplate jdbc;

    public DeliveryPolicy(@Qualifier("appJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, transactionManager = "appTransactionManager")
    public Optional<Policy> forCategory(UUID tenantId, String category) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
        return Optional.ofNullable(jdbc.query(
                """
                SELECT channels, timezone, default_locale, quiet_from, quiet_to
                  FROM notification.channel_policy WHERE category = ?
                """,
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    LocalTime from = rs.getObject("quiet_from", LocalTime.class);
                    LocalTime to = rs.getObject("quiet_to", LocalTime.class);
                    return new Policy(
                            category,
                            List.of((String[]) rs.getArray("channels").getArray()),
                            ZoneId.of(rs.getString("timezone")),
                            rs.getString("default_locale"),
                            from,
                            to);
                },
                category));
    }

    /**
     * @param channels ordered fallback. A customer with no address for the first falls through to
     *     the next; one with no address for any is a suppression, never a silent drop.
     * @param zone the tenant's own, matching the ledger's business-date rule so two services never
     *     disagree about what "today" means
     * @param defaultLocale what to write in when the customer never said, and the second attempt
     *     when they did but the tenant has published nothing in that language
     */
    public record Policy(
            String category,
            List<String> channels,
            ZoneId zone,
            String defaultLocale,
            LocalTime quietFrom,
            LocalTime quietTo) {

        public boolean isQuiet(Instant at) {
            if (TRANSACTIONAL.equals(category) || quietFrom == null) {
                return false;
            }
            LocalTime now = at.atZone(zone).toLocalTime();
            // A window that wraps midnight is the common case — 21:00 to 07:00 — so the two
            // comparisons are not symmetric and cannot be collapsed into one range check.
            return quietFrom.isBefore(quietTo)
                    ? !now.isBefore(quietFrom) && now.isBefore(quietTo)
                    : !now.isBefore(quietFrom) || now.isBefore(quietTo);
        }
    }
}
