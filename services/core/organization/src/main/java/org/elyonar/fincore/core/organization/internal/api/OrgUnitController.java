package org.elyonar.fincore.core.organization.internal.api;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.auth.Authorization;
import org.elyonar.fincore.core.organization.api.UnitClaims;
import org.elyonar.fincore.core.organization.internal.UnitRecords;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tenant's operational structure (ADR 0012).
 *
 * <p>What a unit is <em>not</em> is the design: not a legal entity (that boundary is the tenant),
 * not a booking unit (the ledger is one book per tenant and stays organization-agnostic), not a
 * jurisdiction (that is a country pack). This surface manages operational scope — branches,
 * regions, business lines — and who is assigned where.
 *
 * <p>Assignments recorded here are the system of record; enforcement reads the token's
 * {@code units} claim, which identity provisioning derives from these rows. The indirection is
 * deliberate and documented in the ADR: a caller must never assert its own scope, so the claim
 * travels the same trust path permissions do.
 *
 * <p>Deriving it is this surface's job, though, and for a while it was nobody's: assigning here
 * wrote the row and left the claim alone, so a teller moved to a branch through this screen was
 * recorded in the branch and still carried the old scope in every token they minted afterwards.
 * The two stores only agreed if the same change happened to be made through
 * {@code PUT /v1/users/{id}/units}, which did both. Both paths now do.
 */
@Tag(name = "Organization", description = "Organizational units — branches, regions, business lines — and assignments")
@RestController
@RequestMapping("/v1/org-units")
public class OrgUnitController {

    private final UnitRecords units;
    private final UnitClaims claims;

    /**
     * @param claims optional — absent in a deployment with no directory to provision, where an
     *     assignment is a record and nothing more. Injected as a provider rather than required, so
     *     this module stays standalone-runnable (ADR 0006) instead of failing to start without the
     *     module that happens to hold an identity client today.
     */
    public OrgUnitController(UnitRecords units, ObjectProvider<UnitClaims> claims) {
        this.units = units;
        this.claims = claims.getIfAvailable(() -> UnitClaims.NONE);
    }

    /** Creates a unit. The tenant comes from the token, never the body. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UnitRecords.Unit create(@RequestBody CreateUnit request) {
        var identity = Authorization.require("org:manage");
        return units.create(
                identity.tenantId(),
                request.code(),
                request.name(),
                request.unitType(),
                request.parentCode(),
                Authorization.initiatedBy());
    }

    /** The tenant's units. */
    @GetMapping
    public List<UnitRecords.Unit> list() {
        var identity = Authorization.require("org:read");
        return units.list(identity.tenantId());
    }

    /** One unit. */
    @GetMapping("/{id}")
    public UnitRecords.Unit read(@PathVariable UUID id) {
        var identity = Authorization.require("org:read");
        UnitRecords.Unit unit = units.read(identity.tenantId(), id);
        if (unit == null) {
            throw new UnitRecords.NoSuchUnit();
        }
        return unit;
    }

    /**
     * Closes a unit. History stays attributed to it — a closed branch's tills and approvals keep
     * naming it, which is the audit answering "where", years later, about a branch that no longer
     * exists.
     */
    @PostMapping("/{id}/close")
    public void close(@PathVariable UUID id) {
        var identity = Authorization.require("org:manage");
        units.close(identity.tenantId(), id);
    }

    /** Assigns a principal to a unit, attributed, and moves their claim with it. */
    @PostMapping("/{id}/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    public AssignmentCreated assign(@PathVariable UUID id, @RequestBody Assign request) {
        var identity = Authorization.require("org:manage");
        UUID assignmentId =
                units.assign(identity.tenantId(), id, request.principal(), Authorization.initiatedBy());
        refreshClaim(identity.tenantId(), request.principal());
        return new AssignmentCreated(assignmentId);
    }

    /** Revokes a live assignment, attributed, and drops it from their claim. History is kept. */
    @PostMapping("/{id}/assignments/revoke")
    public void revoke(@PathVariable UUID id, @RequestBody Assign request) {
        var identity = Authorization.require("org:manage");
        units.revoke(identity.tenantId(), id, request.principal(), Authorization.initiatedBy());
        refreshClaim(identity.tenantId(), request.principal());
    }

    /**
     * Re-derives the principal's {@code units} claim from the rows just written.
     *
     * <p>Read back rather than computed from the request, so the claim is provisioned from the
     * system of record and not from what the caller asked for. The row is written first and this
     * follows it, matching {@code PUT /v1/users/{id}/units}: the store that can refuse goes first,
     * so the two cannot disagree because half of it succeeded.
     */
    private void refreshClaim(UUID tenantId, String principal) {
        claims.refresh(tenantId, principal, units.assignmentsOf(tenantId, principal));
    }

    /** The live assignments of a unit — what identity provisioning reads. */
    @GetMapping("/{id}/assignments")
    public List<UnitRecords.Assignment> assignments(@PathVariable UUID id) {
        var identity = Authorization.require("org:read");
        return units.assignments(identity.tenantId(), id);
    }

    /** @param parentCode optional — the code of an active parent unit */
    public record CreateUnit(String code, String name, String unitType, String parentCode) {}

    /** @param principal exactly as tokens spell it, e.g. {@code user:ada.o} */
    public record Assign(String principal) {}

    public record AssignmentCreated(UUID assignmentId) {}
}
