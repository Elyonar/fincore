package org.elyonar.fincore.core.app;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Registers the development tenant at startup — the caller {@code register} never had.
 *
 * <p>CHANGELOG v1.12 records the gap honestly: "nothing provisions a tenant … a tenant is created
 * by the test suite or by hand." The control plane that owns provisioning is still designed and
 * unbuilt; until it lands, this runner is the one sanctioned caller, so a fresh
 * {@code docker compose up} yields a Core that answers requests rather than 404ing every tenant.
 *
 * <p>Loud, and off by default: it runs only when {@code fincore.core.dev-tenant-id} is set —
 * compose sets it, deployments must not — mirroring the dev identity resolver's posture and the
 * ledger's seeder exactly.
 */
@Component
@ConditionalOnProperty("fincore.core.dev-tenant-id")
public class DevTenantSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevTenantSeeder.class);

    private final TenantRegistry tenants;
    private final UUID tenantId;

    public DevTenantSeeder(TenantRegistry tenants, @Value("${fincore.core.dev-tenant-id}") UUID tenantId) {
        this.tenants = tenants;
        this.tenantId = tenantId;
    }

    @Override
    public void run(ApplicationArguments args) {
        tenants.register(tenantId, "Development tenant", "system:dev-seeder");
        log.warn("  │  Tenant     DEV SEEDER registered {} — never set fincore.core.dev-tenant-id in a deployment",
                tenantId);
    }
}
