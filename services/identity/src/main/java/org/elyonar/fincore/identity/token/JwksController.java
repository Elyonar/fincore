package org.elyonar.fincore.identity.token;

import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The published verification keys — the one endpoint every other service's startup depends on.
 *
 * <p>Serves the active key and, mid-rotation, the outgoing one, so tokens signed before a
 * rotation verify until they expire. Public by definition and cacheable: verifiers fetch and
 * cache, which is what keeps identity out of the per-request path (PRD §6.1).
 */
@RestController
public class JwksController {

    private final KeyRing keys;

    public JwksController(KeyRing keys) {
        this.keys = keys;
    }

    @GetMapping("/.well-known/jwks.json")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)))
                .body(keys.published().toJSONObject());
    }
}
