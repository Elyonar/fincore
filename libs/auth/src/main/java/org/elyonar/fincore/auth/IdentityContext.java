package org.elyonar.fincore.auth;

import java.util.Set;
import java.util.UUID;

/**
 * Who is asking, on whose behalf, and with what permission.
 *
 * <p>Three identities travel with every money-path request and each is established by a different
 * party (ADR 0009): the <em>principal</em> is the human or system job that asked, verified by the
 * identity provider; the <em>service</em> is the calling system, verified by mutual TLS; the
 * <em>tenant</em> is whose money is moving.
 *
 * <p><strong>The tenant comes from the token, never from a header.</strong> A header-supplied
 * tenant is a caller assertion, and if it is wrong then every downstream isolation control —
 * row-level security included — faithfully enforces the wrong boundary. That is worse than no
 * isolation, because it looks like isolation.
 *
 * <p>Principal and service are kept apart rather than collapsed into one "user" because examiners
 * ask two different questions: who authorized this, and which system performed it. A single field
 * answers neither properly, which is why the ledger records {@code initiated_by} and
 * {@code executed_by} as separate columns.
 *
 * @param tenantId whose money. Never null.
 * @param principal the human or system job that asked, e.g. {@code user:ada.o@branch-01} or
 *     {@code system:core-saga-compensator}. Never null.
 * @param serviceIdentity the calling service, e.g. {@code core-orchestration}, or null when the
 *     request arrived directly at the edge rather than from another service.
 * @param permissions what this caller may do. Never null; may be empty, which denies everything.
 * @param tokenId the token's unique id, carried so an audit trail can tie an action back to the
 *     credential that authorized it. May be null for service-only calls.
 */
public record IdentityContext(
        UUID tenantId,
        String principal,
        String serviceIdentity,
        Set<String> permissions,
        String tokenId) {

    public IdentityContext {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (principal == null || principal.isBlank()) {
            throw new IllegalArgumentException("principal is required");
        }
        // Copied and frozen: a caller holding a reference to the original set must not be able to
        // grant itself a permission after the context was built.
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    /** True when this caller holds the named permission. Absent permission is always a denial. */
    public boolean has(String permission) {
        return permissions.contains(permission);
    }

    /**
     * True when the call arrived from the named service.
     *
     * <p>Used by a service's own caller allowlist — the ledger accepts writes only from
     * {@code core-orchestration} — which is enforcement the owning service does, never the
     * gateway.
     */
    public boolean calledBy(String service) {
        return serviceIdentity != null && serviceIdentity.equals(service);
    }
}
