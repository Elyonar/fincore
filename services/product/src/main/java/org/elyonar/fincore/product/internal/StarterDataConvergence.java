package org.elyonar.fincore.product.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Gives every registered tenant its starter vocabulary on every boot.
 *
 * <p>A migration cannot do this job and neither can registration alone. The migration runs before
 * any tenant exists, so it seeds nobody; registration is bypassed entirely by the {@code psql}
 * provisioning script that is currently the only way a tenant reaches this service. Between them
 * they left institutions with an empty vocabulary and no error anywhere — the failure that reads as
 * "nothing configured yet" on a screen and as a healthy service in every log.
 *
 * <p>Runs after Flyway (an {@code ApplicationRunner} starts once the context is refreshed and the
 * data sources are migrated) and before the startup banner, so the reported count is true.
 *
 * <p>This does not make provisioning correct, and is not meant to. It makes the vocabulary
 * self-healing, which is a different and weaker claim: a tenant registered by any route has what it
 * needs by the next restart at the latest. The provisioning surface that would seed at registration
 * time for every route is separate work.
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
        log.info("  │  Vocabulary product types converged for {} tenant(s)", converged);
    }
}
