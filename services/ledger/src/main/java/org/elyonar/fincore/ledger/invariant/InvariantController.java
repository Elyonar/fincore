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
import org.elyonar.fincore.ledger.shared.InvariantStatus;

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
            return new ReportResponse(null, null, null, null, InvariantStatus.NO_RUN_YET.value(), 0, 0, List.of());
        }
        return new ReportResponse(
                Long.toString(report.runId()),
                report.startedAt().toString(),
                report.completedAt() == null ? null : report.completedAt().toString(),
                report.scope(),
                // A run with no completion time is still in flight; reporting it CLEAN would be a
                // lie of omission at exactly the moment someone is checking.
                report.completedAt() == null
                        ? InvariantStatus.RUNNING.value()
                        : (report.clean() ? InvariantStatus.CLEAN.value() : InvariantStatus.VIOLATIONS.value()),
                report.violations(),
                report.exposures(),
                // The findings themselves, not just their counts: "3 violations" without the
                // accounts they name is not actionable at three in the morning.
                report.findings().stream()
                        .map(f -> new FindingResponse(f.kind().name(), f.invariant(), f.subject(), f.detail()))
                        .toList());
    }

    @PostMapping("/invariants/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "Run verification now",
            description =
                    "Queues a run and returns 202 immediately; poll `GET /v1/invariants` for the"
                        + " result. Full verification is expensive, so a request within the cooldown"
                        + " returns 429 and a run already in flight returns that one rather than"
                        + " starting a second.\n\n"
                        + "Findings split into **violations** and **authorized exposures**. A violation"
                        + " is always a bug and should page. An exposure is the routine, explained"
                        + " consequence of a reversal bypassing the negative-balance guard — tracked and"
                        + " aged, never alarmed on. Keeping them apart is what lets 'zero violations'"
                        + " stay both achievable and meaningful.")
    public ReportResponse run(HttpServletRequest http) {
        // 202 means queued, and now genuinely is: this returns as soon as the run is registered.
        // Running the scan inline would have made the endpoint a denial-of-service lever pointed
        // at the ledger's own database — the thing the contract said it must not be.
        InvariantReport queued = invariants.requestRun(tenants.resolve(http));
        return new ReportResponse(
                Long.toString(queued.runId()),
                queued.startedAt().toString(),
                null,
                queued.scope(),
                InvariantStatus.QUEUED.value(),
                0,
                0,
                List.of());
    }

    public record ReportResponse(
            String runId,
            String startedAt,
            String completedAt,
            String scope,
            @Schema(allowableValues = {"CLEAN", "VIOLATIONS", "QUEUED", "RUNNING", "NO_RUN_YET"})
                    String status,
            long violations,
            long exposures,
            List<FindingResponse> findings) {}

    public record FindingResponse(
            @Schema(allowableValues = {"VIOLATION", "AUTHORIZED_EXPOSURE"}) String kind,
            String invariant,
            String subject,
            String detail) {}
}
