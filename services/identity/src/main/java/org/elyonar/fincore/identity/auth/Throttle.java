package org.elyonar.fincore.identity.auth;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;
import org.elyonar.fincore.identity.internal.IdentityProperties;
import org.elyonar.fincore.identity.internal.Tx;
import org.springframework.stereotype.Component;

/**
 * Login throttling (design.md D16): rows, not memory, so instances agree; silent, so the lock is
 * never an oracle. Account scope locks after N failures; source scope answers 429 to floods.
 * Scopes are rowed for usernames that do not exist too, for the same oracle reason.
 *
 * <p>All methods run inside the caller's tenant transaction.
 */
@Component
public class Throttle {

    private final Tx tx;
    private final IdentityProperties.Throttle config;

    public Throttle(Tx tx, IdentityProperties properties) {
        this.tx = tx;
        this.config = properties.getThrottle();
    }

    public boolean accountLocked(UUID tenantId, String username) {
        Instant lockedUntil = lockedUntil(tenantId, accountScope(username));
        return lockedUntil != null && lockedUntil.isAfter(Instant.now());
    }

    public boolean sourceFlooded(UUID tenantId, String source) {
        Integer count = tx.jdbc()
                .query(
                        "SELECT failure_count FROM identity.login_throttle"
                                + " WHERE tenant_id = ? AND scope = ? AND window_start > ?",
                        rs -> rs.next() ? rs.getInt(1) : null,
                        tenantId,
                        sourceScope(source),
                        java.sql.Timestamp.from(windowStart()));
        return count != null && count >= config.getSourceFailuresBeforeDelay();
    }

    /**
     * One failure against both scopes; locks the account scope at the threshold.
     *
     * <p>In its own transaction, because every caller records a failure and then throws. Inside the
     * caller's transaction the throw rolled this straight back, so the count never climbed, the
     * lock never engaged, and six wrong passwords left exactly as much evidence as none — the
     * control existed in the source and nowhere in the database.
     */
    public void recordFailure(UUID tenantId, String username, String source) {
        tx.independently(tenantId, () -> {
            bump(tenantId, accountScope(username), true);
            bump(tenantId, sourceScope(source), false);
            return null;
        });
    }

    public void clear(UUID tenantId, String username) {
        tx.jdbc()
                .update(
                        "DELETE FROM identity.login_throttle WHERE tenant_id = ? AND scope = ?",
                        tenantId,
                        accountScope(username));
    }

    public void unlock(UUID tenantId, String username) {
        clear(tenantId, username);
    }

    private void bump(UUID tenantId, String scope, boolean lockable) {
        Instant now = Instant.now();
        // One statement, arbitration in the database: concurrent failures for one scope serialize
        // on the primary key rather than racing in application code.
        tx.jdbc()
                .update(
                        "INSERT INTO identity.login_throttle (tenant_id, scope, failure_count, window_start)"
                            + " VALUES (?,?,1,?)"
                            + " ON CONFLICT (tenant_id, scope) DO UPDATE SET"
                            + "   failure_count = CASE WHEN identity.login_throttle.window_start < ?"
                            + "     THEN 1 ELSE identity.login_throttle.failure_count + 1 END,"
                            + "   window_start = CASE WHEN identity.login_throttle.window_start < ?"
                            + "     THEN ? ELSE identity.login_throttle.window_start END",
                        tenantId,
                        scope,
                        java.sql.Timestamp.from(now),
                        java.sql.Timestamp.from(windowStart()),
                        java.sql.Timestamp.from(windowStart()),
                        java.sql.Timestamp.from(now));
        if (lockable) {
            tx.jdbc()
                    .update(
                            "UPDATE identity.login_throttle SET locked_until = ?"
                                    + " WHERE tenant_id = ? AND scope = ? AND failure_count >= ?",
                            java.sql.Timestamp.from(now.plus(config.getLockMinutes(), ChronoUnit.MINUTES)),
                            tenantId,
                            scope,
                            config.getAccountFailuresBeforeLock());
        }
    }

    private Instant lockedUntil(UUID tenantId, String scope) {
        java.sql.Timestamp ts = tx.jdbc()
                .query(
                        "SELECT locked_until FROM identity.login_throttle"
                                + " WHERE tenant_id = ? AND scope = ?",
                        rs -> rs.next() ? rs.getTimestamp(1) : null,
                        tenantId,
                        scope);
        return ts == null ? null : ts.toInstant();
    }

    private Instant windowStart() {
        return Instant.now().minus(config.getWindowMinutes(), ChronoUnit.MINUTES);
    }

    private static String accountScope(String username) {
        return "user:" + username.toLowerCase(Locale.ROOT);
    }

    private static String sourceScope(String source) {
        return "src:" + source;
    }
}
