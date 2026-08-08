package org.elyonar.fincore.ledger.tenant;

import java.util.UUID;
import org.elyonar.fincore.ledger.shared.LedgerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Registers the development tenant at startup — the first caller {@code register} has ever had.
 *
 * <p>{@code db/init/20-dev-tenant.sql} could not seed it: init scripts run before Flyway creates
 * the table, so the script printed a note asking the operator to paste an INSERT by hand — a
 * provisioning path that is a comment is the same category of gap as an endpoint that is a plan.
 * This runner exists so a fresh {@code docker compose up} can post money without psql surgery.
 *
 * <p>Loud, and off by default. It runs only when {@code ledger.dev-tenant-id} is set — compose
 * sets it, deployments must not — and it announces itself the way the dev identity resolver does,
 * because a component that silently provisions tenants is a component that one day silently
 * provisions the wrong one. Real provisioning belongs to the control plane when it exists
 * (Core CHANGELOG v1.12 records that gap honestly).
 */
@Component
@ConditionalOnProperty(LedgerProperties.DEV_TENANT_ID)
public class DevTenantSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevTenantSeeder.class);

    private final TenantRegistry tenants;
    private final UUID tenantId;

    public DevTenantSeeder(
            TenantRegistry tenants, @Value("${" + LedgerProperties.DEV_TENANT_ID + "}") UUID tenantId) {
        this.tenants = tenants;
        this.tenantId = tenantId;
    }

    @Override
    public void run(ApplicationArguments args) {
        tenants.register(tenantId, "Development tenant", "system:dev-seeder");
        log.warn("  │  Tenant     DEV SEEDER registered {} — never set {} in a deployment",
                tenantId, LedgerProperties.DEV_TENANT_ID);
    }
}
