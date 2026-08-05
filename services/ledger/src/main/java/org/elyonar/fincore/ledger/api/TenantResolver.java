package org.elyonar.fincore.ledger.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Establishes which tenant a request belongs to.
 *
 * <p><strong>Interim.</strong> The design has tenant scoping arrive implicitly from a validated
 * JWT or service identity, and it is never taken from a request body. Identity does not exist yet,
 * so the tenant arrives in a header — but the header is read in exactly one place, so replacing it
 * with token extraction later touches this class and nothing else.
 *
 * <p>Until then the real control is the transport: mTLS plus the service-identity allowlist, with
 * Orchestration as the only writer. A header alone is not authentication and is not treated as
 * such.
 */
@Component
public class TenantResolver {

    public static final String TENANT_HEADER = "X-Tenant-Id";

    public UUID resolve(HttpServletRequest request) {
        String raw = request.getHeader(TENANT_HEADER);
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(TENANT_HEADER + " is required until Identity issues tokens");
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(TENANT_HEADER + " must be a UUID");
        }
    }
}
