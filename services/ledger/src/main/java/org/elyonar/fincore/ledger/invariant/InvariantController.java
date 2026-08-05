package org.elyonar.fincore.ledger.invariant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.elyonar.fincore.ledger.api.TenantHeader;
import org.elyonar.fincore.ledger.api.TenantResolver;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The ledger's argument for its own correctness. */
@RestController
@RequestMapping("/v1")
@Tag(name = "Invariants", description = "Verification and the authorized-exposure report")
@TenantHeader
public class InvariantController {

    private final InvariantService invariants;
    private final TenantResolver tenants;

    public InvariantController(InvariantService invariants, TenantResolver tenants) {
        this.invariants = invariants;
        this.tenants = tenants;
    }

    @GetMapping("/invariants")
    @Operation(
            summary = "Fetch the latest completed report",
            description =
                    "Fetches only. An endpoint that could trigger a full-history scan on demand would be a"
                        + " denial-of-service lever pointed at the ledger's own database.")
    public ReportResponse latest(HttpServletRequest http) {
        InvariantReport report = invariants.latest(tenants.resolve(http));
        if (report == null) {
            return new ReportResponse(null, null, null, null, "NO_RUN_YET", 0, 0, List.of());
        }
        return new ReportResponse(
                Long.toString(report.runId()),
                report.startedAt().toString(),
                report.completedAt() == null ? null : report.completedAt().toString(),
                report.scope(),
                report.clean() ? "CLEAN" : "VIOLATIONS",
                report.violations(),
                report.exposures(),
                List.of());
    }

    @PostMapping("/invariants/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "Run verification now",
            description =
                    "Returns findings split into **violations** and **authorized exposures**. A violation"
                        + " is always a bug and should page. An exposure is the routine, explained"
                        + " consequence of a reversal bypassing the negative-balance guard — tracked and"
                        + " aged, never alarmed on. Keeping them apart is what lets 'zero violations' stay"
                        + " both achievable and meaningful.")
    public ReportResponse run(HttpServletRequest http) {
        InvariantReport report = invariants.verify(tenants.resolve(http));
        return new ReportResponse(
                Long.toString(report.runId()),
                report.startedAt().toString(),
                report.completedAt().toString(),
                report.scope(),
                report.clean() ? "CLEAN" : "VIOLATIONS",
                report.violations(),
                report.exposures(),
                report.findings().stream()
                        .map(f -> new FindingResponse(f.kind().name(), f.invariant(), f.subject(), f.detail()))
                        .toList());
    }

    public record ReportResponse(
            String runId,
            String startedAt,
            String completedAt,
            String scope,
            @Schema(allowableValues = {"CLEAN", "VIOLATIONS", "NO_RUN_YET"}) String status,
            long violations,
            long exposures,
            List<FindingResponse> findings) {}

    public record FindingResponse(
            @Schema(allowableValues = {"VIOLATION", "AUTHORIZED_EXPOSURE"}) String kind,
            String invariant,
            String subject,
            String detail) {}
}
