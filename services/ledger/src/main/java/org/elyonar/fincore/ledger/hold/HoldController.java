package org.elyonar.fincore.ledger.hold;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.elyonar.fincore.ledger.api.Money;
import org.elyonar.fincore.ledger.api.TenantHeader;
import org.elyonar.fincore.ledger.api.TenantResolver;
import org.elyonar.fincore.ledger.shared.ErrorCode;
import org.elyonar.fincore.ledger.shared.LedgerException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Reserving funds without moving them. */
@RestController
@RequestMapping("/v1")
@Tag(name = "Holds", description = "Reserving funds without moving them")
@TenantHeader
public class HoldController {

    private final HoldService holds;
    private final TenantResolver tenants;

    public HoldController(HoldService holds, TenantResolver tenants) {
        this.holds = holds;
        this.tenants = tenants;
    }

    @PostMapping("/holds")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Place a hold",
            description =
                    "Reduces `available` (current − holds) without writing an entry. Idempotent, so a"
                        + " retry can never double-reserve. An expiry is mandatory: the ledger"
                        + " deliberately cannot express a permanent lien on a customer's money.\n\n"
                        + "Capture is **not** an operation here — pass `consumeHoldId` on a posting, so"
                        + " the reservation and the money movement commit together.")
    public PlaceHoldResponse place(HttpServletRequest http, @RequestBody PlaceHoldRequest request) {
        UUID id =
                holds.place(
                        new PlaceHoldCommand(
                                tenants.resolve(http),
                                request.idempotencyKey(),
                                UUID.fromString(request.accountId()),
                                Money.fromWire(request.amountMinor(), "amountMinor"),
                                request.currency(),
                                Instant.parse(request.expiresAt())));
        return new PlaceHoldResponse(id.toString());
    }

    @GetMapping("/holds/{id}")
    @Operation(
            summary = "Read a hold's state",
            description =
                    "For crash recovery: a restarted orchestrator asks what happened to its reservation"
                        + " instead of probing by releasing it. Asking must never be the thing that"
                        + " changes the answer.")
    public HoldResponse get(HttpServletRequest http, @PathVariable UUID id) {
        HoldView hold = holds.find(tenants.resolve(http), id);
        if (hold == null) {
            throw new LedgerException(ErrorCode.ACCOUNT_NOT_FOUND, "unknown hold " + id);
        }
        return new HoldResponse(
                hold.id().toString(),
                hold.accountId().toString(),
                Money.toWire(hold.amountMinor()),
                hold.currency(),
                hold.status(),
                hold.expiresAt().toString());
    }

    @PostMapping("/holds/{id}/release")
    @Operation(
            summary = "Release a hold",
            description =
                    "Returns the transition that actually happened, never a bare success. A caller whose"
                        + " reservation expired before it decided to capture must *learn* that, rather"
                        + " than receive a success-shaped no-op while the funds it believes are reserved"
                        + " get spent by someone else.")
    public ReleaseHoldResponse release(HttpServletRequest http, @PathVariable UUID id) {
        return new ReleaseHoldResponse(id.toString(), holds.release(tenants.resolve(http), id).name());
    }

    // ------------------------------------------------------------------- DTOs

    public record PlaceHoldRequest(
            @Schema(example = "orch-hold-000124") String idempotencyKey,
            @Schema(format = "uuid") String accountId,
            @Schema(example = "30000", description = "Integer minor units; number or string") Object amountMinor,
            @Schema(example = "NGN") String currency,
            @Schema(
                            example = "2026-08-06T12:00:00Z",
                            description = "Mandatory, and bounded by the tenant's maximum hold TTL")
                    String expiresAt) {}

    public record PlaceHoldResponse(String holdId) {}

    public record HoldResponse(
            String holdId,
            String accountId,
            String amountMinor,
            String currency,
            @Schema(allowableValues = {"ACTIVE", "RELEASED", "EXPIRED", "CONSUMED"}) String status,
            String expiresAt) {}

    public record ReleaseHoldResponse(
            String holdId,
            @Schema(
                            allowableValues = {
                                "RELEASED_NOW", "ALREADY_RELEASED", "ALREADY_EXPIRED", "ALREADY_CONSUMED"
                            },
                            description = "The transition that actually happened")
                    String outcome) {}
}
