package org.elyonar.fincore.ledger.api;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What this service is, at the root.
 *
 * <p>A 404 at {@code /} is technically correct and operationally unhelpful: it is the first thing a
 * person types and the default target of many uptime checks, and "not found" tells neither whether
 * the service is alive or merely misrouted. This answers 200 with enough to navigate from.
 *
 * <p>Deliberately empty of anything worth protecting — a name, a version, and links. A ledger's
 * root is read by more people than its configuration is, so nothing here names a tenant, an
 * account, or a setting.
 */
@Tag(name = "Service", description = "Identity, health and documentation links")
@RestController
public class ServiceInfoController {

    private final String version;

    public ServiceInfoController(@Value("${fincore.build.version:0.0.1-SNAPSHOT}") String version) {
        this.version = version;
    }

    @GetMapping("/")
    public Map<String, Object> index() {
        return Map.of(
                "service", "fincore-ledger",
                "description", "The single source of monetary truth. Accounts, entries, balances, holds.",
                "version", version,
                "docs", "/docs",
                "openapi", "/v3/api-docs",
                "health", "/actuator/health");
    }
}
