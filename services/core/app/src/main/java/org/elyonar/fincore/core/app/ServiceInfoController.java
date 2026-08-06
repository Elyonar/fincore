package org.elyonar.fincore.core.app;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What this service is, at the root.
 *
 * <p>A 404 at {@code /} is technically correct and operationally unhelpful: it is the first thing a
 * person types, the first thing a load balancer or uptime check is pointed at by default, and the
 * answer "not found" tells neither of them whether the service is alive or misrouted. This answers
 * 200 with enough to navigate from.
 *
 * <p>Deliberately unauthenticated, and deliberately empty of anything worth protecting — a name, a
 * version, and links. Nothing here reveals a tenant, a customer, or a configuration value.
 */
@RestController
public class ServiceInfoController {

    private final String version;

    public ServiceInfoController(@Value("${fincore.build.version:0.0.1-SNAPSHOT}") String version) {
        this.version = version;
    }

    @GetMapping("/")
    public Map<String, Object> index() {
        return Map.of(
                "service", "fincore-core",
                "description",
                        "Customer, Product and Transaction Orchestration. The only caller of the Ledger's write API.",
                "version", version,
                "docs", "/docs",
                "openapi", "/v3/api-docs",
                "health", "/actuator/health");
    }
}
