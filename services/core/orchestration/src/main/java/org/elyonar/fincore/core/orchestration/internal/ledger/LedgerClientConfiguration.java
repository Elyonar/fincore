package org.elyonar.fincore.core.orchestration.internal.ledger;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;
import org.elyonar.fincore.core.orchestration.api.CoreProperties;

/**
 * The Ledger client bean.
 *
 * <p>Wired inside orchestration rather than in {@code app}, because a client is an internal of this
 * module and nothing outside may reference one. That was not a judgement call — the first version
 * put this class in {@code app} and {@code ModuleBoundaryTest} failed the build for reaching into
 * {@code orchestration.internal}. The boundary caught its own author.
 */
@Configuration
public class LedgerClientConfiguration {

    @Bean
    public LedgerClient ledgerClient(
            @Value("${" + CoreProperties.LEDGER_BASE_URL + ":http://localhost:8080}") String baseUrl,
            @Value("${" + CoreProperties.LEDGER_CONNECT_TIMEOUT_MS + ":2000}") long connectMs,
            // Shorter than a caller would tolerate waiting. An unresponsive Ledger must become an
            // UNKNOWN we retry, not a thread held until someone gives up.
            @Value("${" + CoreProperties.LEDGER_READ_TIMEOUT_MS + ":5000}") long readMs,
            // Core's service credential for the ledger's jwt mode (ADR 0014). Empty in dev.
            @Value("${fincore.core.ledger.service-token:}") String serviceToken) {
        return new HttpLedgerClient(
                baseUrl,
                Duration.ofMillis(connectMs),
                Duration.ofMillis(readMs),
                JsonMapper.builder().build(),
                serviceToken);
    }
}
