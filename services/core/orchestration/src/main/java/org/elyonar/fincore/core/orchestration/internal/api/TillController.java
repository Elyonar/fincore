package org.elyonar.fincore.core.orchestration.internal.api;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.elyonar.fincore.auth.Authorization;
import org.elyonar.fincore.core.orchestration.api.CoreException;
import org.elyonar.fincore.core.orchestration.api.ErrorCode;
import org.elyonar.fincore.core.organization.api.OrganizationUnits;
import org.elyonar.fincore.core.orchestration.internal.saga.TillRecords;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Till administration.
 *
 * <p>{@code TillRecords} and the cash path have existed since Core's first slice; this is the
 * surface that was missing — a till could be created only by a test or by raw SQL, while
 * {@code POST /v1/deposits} required one. Same category of gap as the unprovisionable tenant, and
 * the same fix: logic without a route is a plan, not a feature.
 *
 * <p>The branch is validated, not transcribed. A till names an organizational unit, and the
 * Organization module is the authority on whether that unit exists and is a branch (ADR 0012) —
 * so creation resolves the code through the {@code OrganizationUnits} port and refuses
 * {@code UNIT_NOT_FOUND} rather than storing whatever string arrived.
 */
@Tag(name = "Tills", description = "Teller tills — provisioning and closing")
@RestController
@RequestMapping("/v1")
public class TillController {

    private final TillRecords tills;
    private final OrganizationUnits units;

    public TillController(TillRecords tills, OrganizationUnits units) {
        this.tills = tills;
        this.units = units;
    }

    /** Provisions a till against an existing ledger account, inside an existing branch. */
    @PostMapping("/tills")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> open(@RequestBody OpenTill request) {
        var identity = Authorization.require("tills:manage");

        OrganizationUnits.Unit branch = units.activeUnitByCode(identity.tenantId(), request.branchCode());
        if (branch == null || !"BRANCH".equals(branch.unitType())) {
            // Absent, closed, another tenant's, or not a branch — one refusal for all four. The
            // caller learns the unit cannot take a till, and nothing about why.
            throw new CoreException(
                    ErrorCode.UNIT_NOT_FOUND, "no active branch has code " + request.branchCode());
        }

        UUID id =
                tills.open(
                        identity.tenantId(),
                        branch.code(),
                        branch.id(),
                        request.ledgerAccountId(),
                        request.currency(),
                        request.assignedTo());
        return Map.of("tillId", id.toString(), "status", "OPEN");
    }

    /** The tenant's tills. */
    @GetMapping("/tills")
    public List<TillRecords.TillSummary> list() {
        var identity = Authorization.require("tills:read");
        return tills.list(identity.tenantId());
    }

    /** Closes a till. Cash cannot move through it afterwards. */
    @PostMapping("/tills/{id}/close")
    public void close(@PathVariable UUID id) {
        var identity = Authorization.require("tills:manage");
        tills.close(identity.tenantId(), id);
    }

    /** @param branchCode the code of an active organizational unit of type BRANCH */
    public record OpenTill(
            String branchCode, UUID ledgerAccountId, String currency, String assignedTo) {}
}
