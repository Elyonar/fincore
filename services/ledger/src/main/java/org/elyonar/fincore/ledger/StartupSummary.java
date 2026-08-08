package org.elyonar.fincore.ledger;

import java.net.InetAddress;
import java.net.UnknownHostException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Prints what this instance actually is, once it is genuinely serving.
 *
 * <p>Bound to {@code ApplicationReadyEvent} rather than to a startup hook, so the URLs it prints
 * are ones that already answer. A banner that appears before the port is accepting connections
 * teaches people to distrust it.
 *
 * <p>The database line is the one worth having on a ledger. It reports the role the service
 * connected as and whether row-level security can constrain it — because a superuser or a
 * {@code BYPASSRLS} role makes every tenant-isolation policy inert while the catalog still reports
 * RLS enabled. That failure is invisible from the outside and was live in this service once. Now
 * it is impossible to deploy without seeing it stated at every boot.
 *
 * <p>Nothing here logs a credential. Host, port, database and role are operational facts; the
 * password is not, and a ledger's logs are read by more people than its configuration is.
 */
@Component
public class StartupSummary {

    private static final Logger log = LoggerFactory.getLogger(StartupSummary.class);

    private final Environment environment;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public StartupSummary(Environment environment, DataSource dataSource, JdbcTemplate jdbc) {
        this.environment = environment;
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void printSummary() {
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8080"));
        String profiles =
                environment.getActiveProfiles().length == 0
                        ? "default"
                        : String.join(",", environment.getActiveProfiles());

        log.info("");
        log.info("  ┌─────────────────────────────────────────────────────────────────");
        log.info("  │  fincore · ledger — the single source of monetary truth");
        log.info("  ├─────────────────────────────────────────────────────────────────");
        log.info("  │  Local      http://localhost:{}", port);
        log.info("  │  External   http://{}:{}", hostAddress(), port);
        log.info("  │  API docs   http://localhost:{}/swagger-ui/index.html", port);
        log.info("  │  OpenAPI    http://localhost:{}/v3/api-docs", port);
        log.info("  │  Health     http://localhost:{}/actuator/health", port);
        log.info("  ├─────────────────────────────────────────────────────────────────");
        log.info("  │  Profile    {}", profiles);
        log.info("  │  Java       {}", System.getProperty("java.version"));
        log.info("  │  PID        {}", ProcessHandle.current().pid());
        log.info("  ├─────────────────────────────────────────────────────────────────");
        describeDatabase();
        describeEventBackbone();
        describeScheduledWork();
        log.info("  └─────────────────────────────────────────────────────────────────");
        log.info("");
    }

    private void describeDatabase() {
        try (var connection = dataSource.getConnection()) {
            var metadata = connection.getMetaData();
            log.info("  │  Database   {}", sanitize(metadata.getURL()));
            log.info("  │  Engine     {} {}", metadata.getDatabaseProductName(), metadata.getDatabaseProductVersion());

            var identity =
                    jdbc.queryForObject(
                            "SELECT current_user || '|' || rolsuper || '|' || rolbypassrls"
                                    + " FROM pg_roles WHERE rolname = current_user",
                            String.class);
            String[] parts = identity == null ? new String[] {"?", "?", "?"} : identity.split("\\|");
            boolean unconstrained = "t".equals(parts[1]) || "t".equals(parts[2]);

            if (unconstrained) {
                // Loud, because this is not a preference. Every tenant-isolation policy in the
                // schema is inert while the service connects like this.
                log.error("  │  Role       {} — SUPERUSER/BYPASSRLS: ROW-LEVEL SECURITY IS NOT ENFORCED", parts[0]);
            } else {
                log.info("  │  Role       {} (not superuser, not BYPASSRLS — RLS enforced)", parts[0]);
            }

            var schemaVersion =
                    jdbc.queryForObject(
                            "SELECT COALESCE(MAX(version), 'none') FROM flyway_schema_history WHERE success",
                            String.class);
            log.info("  │  Schema     migrated to V{}", schemaVersion);
        } catch (Exception e) {
            log.warn("  │  Database   could not be described: {}", e.getMessage());
        }
    }

    private void describeEventBackbone() {
        String broker = environment.getProperty("fincore.events.broker", "log");
        if ("log".equals(broker)) {
            // Loud on purpose. A deployment on the logging adapter emits nothing at all, and that
            // is invisible from outside — no error, no backlog, just silence downstream.
            log.warn("  │  Events     LOG ADAPTER — nothing is delivered to any broker");
        } else {
            log.info("  │  Events     {} ({})", broker, brokerTarget(broker));
        }
    }

    private String brokerTarget(String broker) {
        return "kafka".equals(broker)
                ? environment.getProperty("spring.kafka.bootstrap-servers", "unset")
                : environment.getProperty("spring.rabbitmq.host", "unset");
    }

    private void describeScheduledWork() {
        log.info(
                "  │  Outbox     relay {}",
                enabled("ledger.outbox.relay.enabled") ? "every " + environment.getProperty("ledger.outbox.relay.interval-ms", "1000") + "ms" : "disabled");
        log.info(
                "  │  Holds      expiry sweep {}",
                enabled("ledger.holds.expiry.enabled") ? "every " + environment.getProperty("ledger.holds.expiry.interval-ms", "30000") + "ms" : "disabled");
        log.info("  │  Invariants {}", enabled("ledger.invariants.enabled") ? "anchors daily, verify hourly" : "disabled");
    }

    private boolean enabled(String property) {
        return environment.getProperty(property, Boolean.class, true);
    }

    /** Strips anything after the database name: a JDBC URL can carry a password in its query string. */
    private static String sanitize(String jdbcUrl) {
        if (jdbcUrl == null) {
            return "unknown";
        }
        int query = jdbcUrl.indexOf('?');
        return query < 0 ? jdbcUrl : jdbcUrl.substring(0, query);
    }

    private static String hostAddress() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }
}
