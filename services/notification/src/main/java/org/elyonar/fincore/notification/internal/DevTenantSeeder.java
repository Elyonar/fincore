package org.elyonar.fincore.notification.internal;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Registers the development tenant at startup, matching the ledger's and Core's seeders.
 *
 * <p>Without it a fresh stack drops every event at the tenant gate: three services each hold a
 * tenant registry, and a dev tenant seeded in two of them is a notification service that
 * silently ignores everything — which is precisely the failure mode this service's design says
 * it must never have quietly.
 *
 * <p>Runs only when {@code fincore.notification.dev-tenant-id} is set — compose sets it,
 * deployments must not — and says so loudly.
 */
@Component
@ConditionalOnProperty("fincore.notification.dev-tenant-id")
public class DevTenantSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevTenantSeeder.class);

    private final TenantRegistry tenants;
    private final UUID tenantId;

    public DevTenantSeeder(
            TenantRegistry tenants, @Value("${fincore.notification.dev-tenant-id}") UUID tenantId) {
        this.tenants = tenants;
        this.tenantId = tenantId;
    }

    @Override
    public void run(ApplicationArguments args) {
        tenants.register(tenantId, "Development tenant", "system:dev-seeder");
        log.warn(
                "  │  Tenant     DEV SEEDER registered {} — never set fincore.notification.dev-tenant-id in a deployment",
                tenantId);
    }
}
