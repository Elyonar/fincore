package org.elyonar.fincore.ledger.support;

import org.elyonar.fincore.ledger.LedgerApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * A Spring context for tests that jqwik drives rather than JUnit.
 *
 * <p>jqwik has its own lifecycle and does not participate in Spring's test context caching, so a
 * property test cannot simply extend {@link LedgerPostgresTest}. One context is started lazily and
 * shared across every property, which keeps generated runs fast enough to be worth running.
 */
public final class SpringHarness {

    private static volatile ConfigurableApplicationContext context;

    private SpringHarness() {}

    public static ConfigurableApplicationContext context() {
        if (context == null) {
            synchronized (SpringHarness.class) {
                if (context == null) {
                    SpringApplication app = new SpringApplication(LedgerApplication.class);
                    app.setAdditionalProfiles("test");
                    app.setDefaultProperties(
                            java.util.Map.of(
                                    "server.port", "0",
                                    "ledger.outbox.relay.enabled", "false",
                                    "ledger.holds.expiry.enabled", "false",
                                    "ledger.invariants.enabled", "false"));
                    context = app.run();
                }
            }
        }
        return context;
    }
}
