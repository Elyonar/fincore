package org.elyonar.fincore.notification.internal.api;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What this service is, for a human who found it by its port.
 *
 * <p>Open by design, like the ledger's and Core's: a load balancer should not need a token to learn
 * it has reached the right process, and there is nothing here a reader could not find in the
 * repository.
 */
@RestController
public class ServiceInfoController {

    @GetMapping("/")
    public Map<String, Object> info() {
        return Map.of(
                "service", "notification",
                "purpose", "consumes domain events and turns them into messages a customer receives",
                "docs", "/docs",
                "health", "/actuator/health",
                "design", "services/notification/docs/design.md");
    }
}
