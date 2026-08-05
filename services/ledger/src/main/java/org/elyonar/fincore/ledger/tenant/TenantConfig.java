package org.elyonar.fincore.ledger.tenant;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The rules a tenant's postings are validated against.
 *
 * <p>Validation always uses the config live at posting time, never the config live at an entry's
 * value date. Applying a superseded config to a backdated entry would make the same request
 * succeed or fail depending on a date the caller chose, which is not a property a ledger should
 * have. History is retained so a past posting's context is reconstructible for audit, not so it
 * can be re-applied.
 */
public record TenantConfig(
        ZoneId businessTimezone, int backdateWindowDays, Duration maxHoldTtl, int maxEntriesPerTransaction) {

    /**
     * The documented platform defaults, used when a tenant has no configuration row.
     *
     * <p>Provisioning seeds an explicit row; these exist so that an unseeded tenant behaves
     * conservatively rather than unpredictably.
     */
    public static final TenantConfig PLATFORM_DEFAULTS =
            new TenantConfig(ZoneId.of("Africa/Lagos"), 30, Duration.ofDays(30), 100);

    /**
     * Today, in the tenant's own zone.
     *
     * <p>Not the server's date. A 00:30 deposit in Lagos belongs to that day's business, and a
     * ledger running on a UTC host would otherwise book it to the previous one — quietly, for
     * every midnight-adjacent transaction, every day.
     */
    public LocalDate businessDate() {
        return LocalDate.now(businessTimezone);
    }

    public LocalDate earliestAllowedValueDate() {
        return businessDate().minusDays(backdateWindowDays);
    }
}
