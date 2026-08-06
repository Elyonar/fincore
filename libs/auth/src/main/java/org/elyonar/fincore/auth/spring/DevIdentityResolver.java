package org.elyonar.fincore.auth.spring;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.elyonar.fincore.auth.IdentityContext;
import org.elyonar.fincore.auth.IdentityResolver;
import org.elyonar.fincore.auth.NotAuthenticatedException;

/**
 * Trusts development headers. <strong>Verifies nothing.</strong>
 *
 * <p>ADR 0010 permits a stand-in while Keycloak realms are being provisioned, under one condition:
 * it must be impossible to enable accidentally in a deployed environment, and it must announce
 * itself loudly. The ledger learned the shape of this problem with its {@code log} event adapter —
 * a component that silently does nothing while the system reports itself working.
 *
 * <p>Two independent, visible mistakes are required to run this in production rather than one:
 * {@code fincore.auth.mode=dev} must be set <em>and</em> a sanctioned profile must be active.
 * Setting the mode alone fails startup with an explanation. A stray environment variable is not
 * enough.
 */
public class DevIdentityResolver implements IdentityResolver {

    static final String TENANT_HEADER = "X-Dev-Tenant-Id";
    static final String PRINCIPAL_HEADER = "X-Dev-Principal";
    static final String PERMISSIONS_HEADER = "X-Dev-Permissions";
    static final String SERVICE_HEADER = "X-Dev-Service";

    public DevIdentityResolver(String[] activeProfiles, String[] sanctionedProfiles) {
        if (!sanctioned(activeProfiles, sanctionedProfiles)) {
            throw new IllegalStateException(
                    """
                    fincore.auth.mode=dev refused to start.

                    This resolver verifies nothing — it reads identity from request headers, so \
                    any caller can claim any tenant and any permission. It is only permitted when \
                    one of the profiles %s is active, and the active profiles are %s.

                    If this is a deployed environment, set fincore.auth.mode=jwt and configure \
                    fincore.auth.issuer-uri. If this is genuinely local development, activate a \
                    development profile."""
                            .formatted(
                                    Arrays.toString(sanctionedProfiles),
                                    activeProfiles.length == 0
                                            ? "[] (none)"
                                            : Arrays.toString(activeProfiles)));
        }
    }

    private static boolean sanctioned(String[] active, String[] sanctioned) {
        for (String profile : active) {
            for (String allowed : sanctioned) {
                if (allowed.equalsIgnoreCase(profile)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public IdentityContext resolve(HttpServletRequest request) {
        String tenant = request.getHeader(TENANT_HEADER);
        if (tenant == null || tenant.isBlank()) {
            throw new NotAuthenticatedException(TENANT_HEADER + " is required in dev mode");
        }
        UUID tenantId;
        try {
            tenantId = UUID.fromString(tenant);
        } catch (IllegalArgumentException e) {
            throw new NotAuthenticatedException(TENANT_HEADER + " must be a UUID", e);
        }

        String principal = request.getHeader(PRINCIPAL_HEADER);
        if (principal == null || principal.isBlank()) {
            throw new NotAuthenticatedException(PRINCIPAL_HEADER + " is required in dev mode");
        }

        return new IdentityContext(
                tenantId,
                principal,
                request.getHeader(SERVICE_HEADER),
                permissions(request.getHeader(PERMISSIONS_HEADER)),
                null);
    }

    private Set<String> permissions(String header) {
        if (header == null || header.isBlank()) {
            return Set.of();
        }
        Set<String> permissions = new LinkedHashSet<>();
        for (String each : header.split(",")) {
            String trimmed = each.trim();
            if (!trimmed.isEmpty()) {
                permissions.add(trimmed);
            }
        }
        return permissions;
    }

    @Override
    public String name() {
        return "dev";
    }

    @Override
    public boolean verifies() {
        return false;
    }
}
