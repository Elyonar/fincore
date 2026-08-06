package org.elyonar.fincore.core.app;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.elyonar.fincore.auth.IdentityResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Prints what this instance actually is, once it is genuinely serving.
 *
 * <p>Bound to {@code ApplicationReadyEvent} rather than a startup hook, so the URLs it prints are
 * ones that already answer. A banner that appears before the port accepts connections teaches
 * people to distrust it.
 *
 * <p>The lines worth having on Core are the ones that are otherwise invisible and expensive to get
 * wrong: which Ledger it will call, whether identity is actually verifying anything, and whether
 * each module connected as its own restricted role. A module running as the wrong role would work
 * perfectly until the day it reached across a schema boundary, and per-role grants are the boundary
 * ADR 0006 rests on.
 *
 * <p>Nothing here logs a credential.
 */
@Component
public class StartupSummary {

    private static final Logger log = LoggerFactory.getLogger(StartupSummary.class);

    private final Environment environment;
    private final IdentityResolver identity;
    private final JdbcTemplate customerJdbc;
    private final JdbcTemplate productJdbc;
    private final JdbcTemplate orchestrationJdbc;
    private final String ledgerUrl;

    public StartupSummary(
            Environment environment,
            IdentityResolver identity,
            @Qualifier("customerJdbcTemplate") JdbcTemplate customerJdbc,
            @Qualifier("productJdbcTemplate") JdbcTemplate productJdbc,
            @Qualifier("orchestrationJdbcTemplate") JdbcTemplate orchestrationJdbc,
            @Value("${fincore.core.ledger.base-url:unset}") String ledgerUrl) {
        this.environment = environment;
        this.identity = identity;
        this.customerJdbc = customerJdbc;
        this.productJdbc = productJdbc;
        this.orchestrationJdbc = orchestrationJdbc;
        this.ledgerUrl = ledgerUrl;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void printSummary() {
        String port =
                environment.getProperty("local.server.port", environment.getProperty("server.port", "8081"));
        String profiles =
                environment.getActiveProfiles().length == 0
                        ? "default"
                        : String.join(",", environment.getActiveProfiles());

        log.info("");
        log.info("  ┌─────────────────────────────────────────────────────────────────");
        log.info("  │  fincore · core — customer, product, orchestration");
        log.info("  ├─────────────────────────────────────────────────────────────────");
        log.info("  │  Local      http://localhost:{}", port);
        log.info("  │  External   http://{}:{}", hostAddress(), port);
        log.info("  │  API docs   http://localhost:{}/docs", port);
        log.info("  │  OpenAPI    http://localhost:{}/v3/api-docs", port);
        log.info("  │  Health     http://localhost:{}/actuator/health", port);
        log.info("  ├─────────────────────────────────────────────────────────────────");
        log.info("  │  Profiles   {}", profiles);
        log.info("  │  Ledger     {}  (the only outbound dependency)", ledgerUrl);
        log.info("  ├─────────────────────────────────────────────────────────────────");
        log.info("  │  Modules    one database role each — the boundary ADR 0006 rests on");
        log.info("  │    customer       {}", roleOf(customerJdbc));
        log.info("  │    product        {}", roleOf(productJdbc));
        log.info("  │    orchestration  {}", roleOf(orchestrationJdbc));
        log.info("  ├─────────────────────────────────────────────────────────────────");
        if (identity.verifies()) {
            log.info("  │  Identity   resolver '{}' — tokens are verified", identity.name());
        } else {
            log.warn("  │  Identity   resolver '{}' — VERIFIES NOTHING. Development only.", identity.name());
        }
        log.info("  └─────────────────────────────────────────────────────────────────");
        log.info("");
    }

    /**
     * The role a module connected as, and whether row-level security can constrain it.
     *
     * <p>A superuser or {@code BYPASSRLS} role makes every tenant policy inert while the catalog
     * still reports RLS enabled — invisible from the outside, and a failure the ledger shipped once.
     */
    private String roleOf(JdbcTemplate jdbc) {
        try {
            return jdbc.queryForObject(
                    "SELECT current_user || CASE WHEN rolsuper OR rolbypassrls"
                            + " THEN '  ⚠ RLS CANNOT CONSTRAIN THIS ROLE' ELSE '  (RLS enforced)' END"
                            + " FROM pg_roles WHERE rolname = current_user",
                    String.class);
        } catch (RuntimeException e) {
            return "unavailable: " + e.getClass().getSimpleName();
        }
    }

    private static String hostAddress() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }
}
