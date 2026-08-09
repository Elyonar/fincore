package org.elyonar.fincore.identity.internal;

import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.identity.token.KeyRing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Names the active posture at startup and warns loudly when a development-only piece is live
 * (service-scaffold §10). A service that silently mints with a throwaway key is the failure this
 * prevents. Also the point where a multi-tenant registry without a named instance tenant refuses
 * to start, rather than letting the first login discover the ambiguity.
 */
@Component
@Order(100)
public class StartupSummary implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupSummary.class);

    private final KeyRing keys;
    private final Tenants tenants;
    private final IdentityProperties properties;

    public StartupSummary(KeyRing keys, Tenants tenants, IdentityProperties properties) {
        this.keys = keys;
        this.tenants = tenants;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<UUID> active = tenants.activeTenants();
        UUID instance = tenants.instanceTenant(); // throws on ambiguity — deliberately fatal here

        log.info("  ┌─ identity");
        log.info("  │  Issuer     {}", properties.getIssuer());
        if (keys.ephemeral()) {
            log.warn("  │  Signing    EPHEMERAL DEV KEY — generated at startup, gone at shutdown."
                    + " Tokens do not survive a restart and nothing else should trust this issuer."
                    + " Never run a deployment without fincore.identity.signing.private-key-pem");
        } else {
            log.info("  │  Signing    deployment-supplied key, kid {}", keys.active().getKeyID());
        }
        if (instance == null) {
            log.warn("  │  Tenant     NONE — {} tenant(s) registered; this instance can authenticate"
                            + " nobody until the manifest seeds one",
                    active.size());
        } else {
            log.info("  │  Tenant     {} ({} registered)", instance, active.size());
        }
        log.info("  │  Access TTL {}s · refresh absolute {}h",
                properties.getAccessTokenTtlSeconds(), properties.getRefresh().getAbsoluteHours());
        log.info("  └─");
    }
}
