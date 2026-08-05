package org.elyonar.fincore.ledger.period;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import org.elyonar.fincore.ledger.api.TenantHeader;
import org.elyonar.fincore.ledger.api.TenantResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Accounting periods: what makes a signed-off statement stay signed off. */
@RestController
@RequestMapping("/v1")
@Tag(name = "Periods", description = "Accounting periods and their close")
@TenantHeader
public class PeriodController {

    private final PeriodService periods;
    private final TenantResolver tenants;

    public PeriodController(PeriodService periods, TenantResolver tenants) {
        this.periods = periods;
        this.tenants = tenants;
    }

    @GetMapping("/periods")
    @Operation(summary = "List closed accounting periods")
    public List<ClosedPeriodResponse> list(HttpServletRequest http) {
        return periods.list(tenants.resolve(http)).stream()
                .map(
                        p ->
                                new ClosedPeriodResponse(
                                        p.periodEnd().toString(), p.closedAt().toString(), p.closedBy()))
                .toList();
    }

    @PostMapping("/periods/{end}/close")
    @Operation(
            summary = "Close an accounting period",
            description =
                    "After this, no posting may carry a value date inside the period. That single rule is"
                        + " what makes a statement over it reproducible forever, and why statements need"
                        + " no snapshot machinery. There is no reopen — a period that could reopen would"
                        + " guarantee nothing.")
    public ClosePeriodResponse close(
            HttpServletRequest http,
            @PathVariable("end") @Schema(example = "2026-07-31") String end,
            @RequestBody ClosePeriodRequest request) {
        LocalDate periodEnd = LocalDate.parse(end);
        periods.close(tenants.resolve(http), periodEnd, request.closedBy());
        return new ClosePeriodResponse(periodEnd.toString(), "CLOSED");
    }

    public record ClosedPeriodResponse(String periodEnd, String closedAt, String closedBy) {}

    public record ClosePeriodRequest(
            @Schema(example = "user:ops.ada", description = "Maker-checker happens upstream") String closedBy) {}

    public record ClosePeriodResponse(String periodEnd, String status) {}
}
