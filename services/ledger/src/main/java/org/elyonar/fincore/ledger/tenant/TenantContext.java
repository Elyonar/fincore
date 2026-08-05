package org.elyonar.fincore.ledger.tenant;

import java.util.UUID;

/**
 * The tenant a unit of work belongs to, for the duration of that work.
 *
 * <p>Held in a thread local because it is ambient to every query in a request rather than a
 * parameter each one should have to thread through. It is populated from validated identity at
 * the edge, never from a request body.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(UUID tenantId) {
        CURRENT.set(tenantId);
    }

    public static UUID require() {
        UUID tenantId = CURRENT.get();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "no tenant context: every ledger operation is tenant-scoped, and RLS will "
                            + "return nothing without it");
        }
        return tenantId;
    }

    /** Must be called on the way out, or the next task on this thread inherits the tenant. */
    public static void clear() {
        CURRENT.remove();
    }
}
