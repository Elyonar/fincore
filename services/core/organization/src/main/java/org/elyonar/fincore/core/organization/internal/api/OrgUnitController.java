package org.elyonar.fincore.core.organization.internal.api;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.auth.Authorization;
import org.elyonar.fincore.core.organization.internal.UnitRecords;
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
 * {@code units} claim, which identity provisioning derives from these rows. The gap between the
 * two is deliberate and documented in the ADR: a caller must never assert its own scope, so the
 * claim travels the same trust path permissions do.
 */
@Tag(name = "Organization", description = "Organizational units — branches, regions, business lines — and assignments")
@RestController
@RequestMapping("/v1/org-units")
public class OrgUnitController {

    private final UnitRecords units;

    public OrgUnitController(UnitRecords units) {
        this.units = units;
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

    /** Assigns a principal to a unit, attributed. */
    @PostMapping("/{id}/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    public AssignmentCreated assign(@PathVariable UUID id, @RequestBody Assign request) {
        var identity = Authorization.require("org:manage");
        UUID assignmentId =
                units.assign(identity.tenantId(), id, request.principal(), Authorization.initiatedBy());
        return new AssignmentCreated(assignmentId);
    }

    /** Revokes a live assignment, attributed. History is kept. */
    @PostMapping("/{id}/assignments/revoke")
    public void revoke(@PathVariable UUID id, @RequestBody Assign request) {
        var identity = Authorization.require("org:manage");
        units.revoke(identity.tenantId(), id, request.principal(), Authorization.initiatedBy());
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
