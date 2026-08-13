package org.elyonar.fincore.customer.internal.api;

import java.util.UUID;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.elyonar.fincore.auth.Authorization;
import org.elyonar.fincore.customer.api.CustomerAdministration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The parts of customer administration Core composes with, over the wire (ADR 0020).
 *
 * <p>Separate from {@code CustomerController}, which is the surface a portal drives directly. These
 * are the operations Core calls in the middle of something larger: opening an account is a ledger
 * account (Core, because only Orchestration may address the ledger), a product check (Product), and
 * the record of who holds it (here). Core owns that sequence because somebody has to own the
 * outcome when the second step succeeds and the third does not.
 *
 * <p><strong>The account number is claimed on this side, inside this service's transaction.</strong>
 * That is the point of {@code link} taking a number rather than returning one for Core to reserve:
 * two tellers opening accounts in the same second must not be issued the same number, and the only
 * place that can be guaranteed is the database that owns the series.
 */
@Tag(name = "Administration", description = "Numbering series, and recording who holds an account")
@RestController
@RequestMapping("/v1")
public class AdministrationController {

    private final CustomerAdministration administration;

    public AdministrationController(CustomerAdministration administration) {
        this.administration = administration;
    }

    @GetMapping("/numbering/{series}")
    public ResponseEntity<CustomerAdministration.NumberSeries> numbering(@PathVariable String series) {
        var identity = Authorization.require("customers:read");
        CustomerAdministration.NumberSeries found = administration.numbering(identity.tenantId(), series);
        return found == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(found);
    }

    @PostMapping("/numbering/{series}")
    public CustomerAdministration.NumberSeries setNumbering(
            @PathVariable String series, @RequestBody Numbering request) {
        var identity = Authorization.require("customers:create");
        return administration.setNumbering(
                identity.tenantId(),
                series,
                request.prefix(),
                request.width(),
                request.nextValue(),
                request.updatedBy());
    }

    /** Records that this customer holds this ledger account, claiming the number here. */
    @PostMapping("/customers/{customerId}/accounts/link")
    public CustomerAdministration.OpenedAccount link(
            @PathVariable UUID customerId, @RequestBody Link request) {
        var identity = Authorization.require("customers:link");
        return administration.linkWithNumber(
                identity.tenantId(),
                customerId,
                request.ledgerAccountId(),
                request.currency(),
                request.role(),
                request.productCode(),
                request.accountNumber());
    }

    /** Already held by somebody. A 409 rather than a 500, because it is an outcome, not a fault. */
    @ExceptionHandler(CustomerAdministration.AccountAlreadyHeld.class)
    public ResponseEntity<Problem> held(CustomerAdministration.AccountAlreadyHeld e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new Problem("ACCOUNT_ALREADY_HELD", e.getMessage()));
    }

    @ExceptionHandler(CustomerAdministration.AccountNumberTaken.class)
    public ResponseEntity<Problem> taken(CustomerAdministration.AccountNumberTaken e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new Problem("ACCOUNT_NUMBER_TAKEN", e.getMessage()));
    }

    /** A series cannot be wound back below what it has already issued. */
    @ExceptionHandler(CustomerAdministration.NumberingRewind.class)
    public ResponseEntity<Problem> rewind(CustomerAdministration.NumberingRewind e) {
        return ResponseEntity.unprocessableEntity().body(new Problem("NUMBERING_REWIND", e.getMessage()));
    }

    public record Numbering(String prefix, int width, long nextValue, String updatedBy) {}

    /** @param accountNumber null to have the series claim the next one */
    public record Link(
            UUID ledgerAccountId,
            String currency,
            String role,
            String productCode,
            String accountNumber) {}

    public record Problem(String code, String message) {}
}
