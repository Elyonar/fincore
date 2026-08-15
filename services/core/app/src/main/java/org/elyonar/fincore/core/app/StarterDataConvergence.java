package org.elyonar.fincore.core.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Gives every registered tenant its starting currencies on every boot.
 *
 * <p>The currency registry has now failed the same institution twice by two different routes, which
 * is why the repair moved here rather than being patched again where it broke. {@code V2} seeded
 * the tenants that existed when it ran and nothing seeded the ones that arrived after; seeding
 * inside registration fixed that and was then bypassed by the {@code psql} provisioning script,
 * which registers a tenant without ever entering that code. Both roads ended at an institution with
 * no currencies — no internal account, no till, no customer account, and every screen reporting
 * that all is well.
 *
 * <p>Runs after Flyway and before the startup banner, so the count it reports is the truth about
 * this boot. Additive only: a currency an institution withdrew on the Currencies screen stays
 * withdrawn, because withdrawing one is a deliberate act and restoring it would undo somebody's
 * decision every time the service restarted.
 */
@Component
@Order(20)
public class StarterDataConvergence implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StarterDataConvergence.class);

    private final TenantRegistry tenants;

    public StarterDataConvergence(TenantRegistry tenants) {
        this.tenants = tenants;
    }

    @Override
    public void run(ApplicationArguments args) {
        int converged = tenants.convergeStarterData();
        log.info("  │  Currencies converged for {} tenant(s)", converged);
    }
}
