package org.elyonar.fincore.ledger.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Establishes which tenant a request belongs to.
 *
 * <p>The design promised that identity would arrive here and touch this class and nothing else —
 * and that is what happened (ADR 0014): resolution delegates to {@link LedgerAuth}, which in
 * {@code jwt} mode verifies a trusted service credential and takes the tenant from a forwarded
 * user token's claim, and in {@code header} mode (development only, double-locked, loudly
 * announced) keeps the old header behavior. Callers that also want posting attribution use
 * {@link #identify} and read the verified initiator when one was forwarded.
 */
@Component
public class TenantResolver {

    public static final String TENANT_HEADER = "X-Tenant-Id";

    private final LedgerAuth auth;

    public TenantResolver(LedgerAuth auth) {
        this.auth = auth;
    }

    public UUID resolve(HttpServletRequest request) {
        return auth.identify(request).tenantId();
    }

    /** Tenant plus, when a user token was forwarded, the verified initiator for attribution. */
    public LedgerAuth.Identity identify(HttpServletRequest request) {
        return auth.identify(request);
    }
}
