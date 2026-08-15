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
 * prevents.
 *
 * <p>This used to refuse to start when several tenants were registered and none was named, because
 * login could not tell institutions apart and would have discovered the ambiguity one credential
 * at a time. A login names its own institution by realm now (ADR 0023), so several tenants is a
 * configuration rather than a fault — it is reported here, at the posture it actually is, and the
 * only thing left to say is which login shape this instance expects.
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
        UUID instance = tenants.instanceTenant(); // null when several are active and none is named

        log.info("  ┌─ identity");
        log.info("  │  Issuer     {}", properties.getIssuer());
        if (keys.ephemeral()) {
            log.warn("  │  Signing    EPHEMERAL DEV KEY — generated at startup, gone at shutdown."
                    + " Tokens do not survive a restart and nothing else should trust this issuer."
                    + " Never run a deployment without fincore.identity.signing.private-key-pem");
        } else {
            log.info("  │  Signing    deployment-supplied key, kid {}", keys.active().getKeyID());
        }
        if (instance != null) {
            log.info("  │  Tenant     {} ({} registered) — login may omit its realm",
                    instance, active.size());
        } else if (active.isEmpty()) {
            log.warn("  │  Tenant     NONE — this instance can authenticate nobody until the"
                    + " manifest seeds one, or one is provisioned over HTTP");
        } else {
            // The sandbox posture of ADR 0023, stated rather than inferred. It is not a warning:
            // an instance holding several institutions on purpose is a supported configuration,
            // and the only consequence a reader needs is that every login must now name one.
            log.info("  │  Tenant     {} registered, none named — every login must carry a realm"
                            + " (set fincore.identity.tenant-id to name a default)",
                    active.size());
        }
        log.info("  │  Access TTL {}s · refresh absolute {}h",
                properties.getAccessTokenTtlSeconds(), properties.getRefresh().getAbsoluteHours());
        log.info("  └─");
    }
}
