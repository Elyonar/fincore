package org.elyonar.fincore.customer.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Gives every registered tenant its starter KYC tiers on every boot.
 *
 * <p>The migration runs before any tenant exists and seeds nobody; the {@code psql} provisioning
 * script bypasses registration and so seeds nobody either. The institution that results cannot
 * onboard a single customer, because a customer needs a tier and there is no tier to give — and
 * nothing in any log says so.
 *
 * <p>Runs after Flyway and before the startup banner. Idempotent: it adds what is missing and never
 * overwrites a tier an institution has renamed to its own vocabulary.
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
        log.info("  │  Vocabulary KYC tiers converged for {} tenant(s)", converged);
    }
}
