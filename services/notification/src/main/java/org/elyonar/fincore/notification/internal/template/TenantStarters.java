package org.elyonar.fincore.notification.internal.template;

import java.util.UUID;

/**
 * Ensures a tenant has the platform's starter wording before anything looks for a template.
 *
 * <p>An interface rather than the class, for the same reason the intake takes its accounts and
 * contact lookups as ones: a test about what the intake decides should be able to say "this tenant
 * has no templates" and have it stay true. With the concrete seeder wired in, the suppression that
 * proves a missing template is unreachable — the platform would have just provided one — and the
 * test would be asserting the seeder's behaviour while claiming to assert the intake's.
 */
@FunctionalInterface
public interface TenantStarters {

    /** Idempotent, and cheap after the first call for a tenant. */
    void ensureFor(UUID tenantId);
}
