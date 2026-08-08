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
 * @param units the organizational units this caller is assigned to, as unit codes (ADR 0012) —
 *     e.g. {@code branch-01}. Never null; may be empty, which is the normal case for machine
 *     identities and for tenants that have not adopted organizational units. Like permissions,
 *     these are asserted by the identity provider, never by the caller.
 */
public record IdentityContext(
        UUID tenantId,
        String principal,
        String serviceIdentity,
        Set<String> permissions,
        String tokenId,
        Set<String> units) {

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
        // Same discipline for units: assigned by the identity provider, immutable in flight.
        units = units == null ? Set.of() : Set.copyOf(units);
    }

    /**
     * The pre-units shape. Kept so a caller that has no organizational scope to assert — a
     * machine identity, a service-to-service call — does not have to spell out an empty set.
     */
    public IdentityContext(
            UUID tenantId,
            String principal,
            String serviceIdentity,
            Set<String> permissions,
            String tokenId) {
        this(tenantId, principal, serviceIdentity, permissions, tokenId, Set.of());
    }

    /** True when this caller holds the named permission. Absent permission is always a denial. */
    public boolean has(String permission) {
        return permissions.contains(permission);
    }

    /**
     * True when this caller is assigned to the named organizational unit.
     *
     * <p>Unit codes come from the token (ADR 0012), the same way permissions do: the identity
     * provider asserts them and nothing downstream takes a caller's word for its own scope. An
     * empty set means the caller carries no organizational scope — which, like an empty permission
     * set, restricts rather than permits wherever a unit is required.
     */
    public boolean assignedTo(String unitCode) {
        return units.contains(unitCode);
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
